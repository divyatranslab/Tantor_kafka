package kafka

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"text/template"
	"time"

	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/pkg/api"
	"io.translab/tantor-agent/pkg/checksum"
)

const defaultKafkaParcelDir = "/srv/apps/tantor/parcels"

type Deployer struct {
	cfg    *config.Config
	client *client.APIClient
	exec   executor.Executor
}

func NewDeployer(cfg *config.Config, client *client.APIClient, exec executor.Executor) *Deployer {
	return &Deployer{
		cfg:    cfg,
		client: client,
		exec:   exec,
	}
}

func (d *Deployer) Deploy(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
	}

	installDir := t.Parameters["kafka_install_dir"]
	if installDir == "" {
		installDir = "/opt/tantor/kafka" // Linux friendly default
	}
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = filepath.Join(installDir, "data")
	}
	mode := deploymentMode(t)
	role := t.Parameters["role"]

	log("Starting cross-platform Kafka Deployment Workflow...")
	log("Deployment mode: %s", mode)

	// 1. Create directories
	if err := d.ensureWritableDir(ctx, d.cfg.Paths.ArtifactsDir); err != nil {
		return logs.String(), err
	}
	if err := d.ensureWritableDir(ctx, installDir); err != nil {
		return logs.String(), err
	}
	if err := d.ensureWritableDir(ctx, dataDir); err != nil {
		return logs.String(), err
	}

	usedParcel, err := d.installFromActiveParcel(ctx, t, installDir, &logs)
	if err != nil {
		return logs.String(), err
	}

	// 2. Download TAR
	if !usedParcel {
		destPath := filepath.Join(d.cfg.Paths.ArtifactsDir, fmt.Sprintf("kafka_%s.tgz", t.TaskID))
		log("Downloading artifact from %s to %s", t.ArtifactURL, destPath)

		downloadedChecksum, err := d.client.DownloadArtifact(t.ArtifactURL, destPath)
		if err != nil {
			return logs.String(), fmt.Errorf("failed to download artifact: %w", err)
		}

		// 3. Verify Checksum
		expectedChecksum := t.Checksum
		if expectedChecksum == "" {
			expectedChecksum = downloadedChecksum
		}
		if err := checksum.VerifySHA256(destPath, expectedChecksum); err != nil {
			os.Remove(destPath)
			return logs.String(), fmt.Errorf("checksum verification failed: %w", err)
		}
		log("Checksum verified successfully")

		// 4. Extract TAR (using tar command which exists on Windows 10+)
		tmpExtractDir := filepath.Join(d.cfg.Paths.ArtifactsDir, "extract_"+t.TaskID)
		os.MkdirAll(tmpExtractDir, 0755)
		_, _, err = d.exec.Run(ctx, "tar", "-xzf", destPath, "-C", tmpExtractDir, "--strip-components=1")
		if err != nil {
			return logs.String(), fmt.Errorf("failed to extract tar: %w", err)
		}

		// 5. Move contents to installDir using Go standard library to avoid OS-specific commands
		err = filepath.Walk(tmpExtractDir, func(path string, info os.FileInfo, err error) error {
			if err != nil || path == tmpExtractDir {
				return err
			}
			relPath, _ := filepath.Rel(tmpExtractDir, path)
			dest := filepath.Join(installDir, relPath)
			if info.IsDir() {
				return os.MkdirAll(dest, 0755)
			}
			data, err := os.ReadFile(path)
			if err == nil {
				os.MkdirAll(filepath.Dir(dest), 0755)
				mode := info.Mode().Perm()
				if mode&0111 == 0 && strings.Contains(relPath, "bin/") {
					mode = 0755 // Ensure bin/ scripts are always executable
				}
				os.Remove(dest) // Force recreation to avoid preserving old permissions
				os.WriteFile(dest, data, mode)
			}
			return nil
		})
		os.RemoveAll(tmpExtractDir)
		log("Artifact extracted to %s", installDir)
	}

	// 5.5 Fix executable bits and SELinux contexts for extracted/copied files.
	if err := d.fixKafkaRuntimePermissions(ctx, installDir, &logs); err != nil {
		return logs.String(), err
	}

	// 6. Setup JMX Exporter
	jmxDir := filepath.Join(installDir, "jmx")
	os.MkdirAll(jmxDir, 0755)
	jmxJarPath := filepath.Join(jmxDir, "jmx_prometheus_javaagent.jar")

	log("Downloading JMX Exporter to %s", jmxJarPath)

	jmxUrl := t.Parameters["jmx_artifact_url"]
	if jmxUrl != "" {
		log("Using JMX Artifact URL from Tantor Server: %s", jmxUrl)
		_, err = d.client.DownloadArtifact(jmxUrl, jmxJarPath)
		if err != nil {
			log("Warning: Failed to download JMX agent from artifact repo: %v", err)
		}
	} else {
		log("Warning: No jmx_artifact_url provided. Falling back to Maven repo1...")
		resp, err := http.Get("https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar")
		if err == nil {
			defer resp.Body.Close()
			out, _ := os.Create(jmxJarPath)
			io.Copy(out, resp.Body)
			out.Close()
		} else {
			log("Warning: Failed to download JMX agent from maven: %v", err)
		}
	}

	if err := d.writeTemplateToSudoFile(ctx, JmxConfigTemplate, nil, filepath.Join(jmxDir, "jmx_config.yml")); err != nil {
		log("Warning: Failed to write JMX config: %v", err)
	}
	if !isUsableJmxAgent(jmxJarPath) {
		log("Warning: JMX agent jar is missing or invalid; Kafka will start without JMX exporter")
		_ = os.Remove(jmxJarPath)
	}

	// 7. Generate Configs
	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	log("Configs generated successfully")

	// 7.5 Format KRaft Storage (only on fresh deploy)
	logDirs := t.Parameters["log_dirs"]
	if logDirs == "" {
		logDirs = filepath.Join(dataDir, "kafka-logs")
	}
	if err := d.ensureWritableDir(ctx, logDirs); err != nil {
		return logs.String(), err
	}

	if mode == "kraft" {
		metaPropsPath := filepath.Join(logDirs, "meta.properties")
		if _, err := os.Stat(metaPropsPath); os.IsNotExist(err) {
			log("Fresh deployment detected — formatting KRaft storage...")
			storageScript := filepath.Join(installDir, "bin", "kafka-storage.sh")
			configPath := filepath.Join(installDir, "config/kraft/server.properties")

			uuidOut, _, err := d.exec.Run(ctx, storageScript, "random-uuid")
			if err != nil {
				return logs.String(), fmt.Errorf("failed to generate cluster UUID: %w", err)
			}
			clusterUUID := strings.TrimSpace(uuidOut)
			log("Generated cluster UUID: %s", clusterUUID)

			_, _, err = d.exec.Run(ctx, storageScript, "format", "-t", clusterUUID, "-c", configPath)
			if err != nil {
				return logs.String(), fmt.Errorf("failed to format KRaft storage: %w", err)
			}
			log("KRaft storage formatted successfully")
		} else {
			log("Existing KRaft metadata found — skipping format (safe re-deploy)")
		}

	} else {
		log("ZooKeeper deployment detected - skipping KRaft storage format")
	}

	// 8. Systemd Service
	if mode == "zookeeper" && roleHasZooKeeper(role) {
		if err := d.createZooKeeperSystemdService(ctx, "root", installDir, t); err != nil {
			return logs.String(), err
		}
		log("ZooKeeper systemd service created")
	}
	if mode == "kraft" || roleHasBroker(role) {
		configPath := filepath.Join(installDir, "config/kraft/server.properties")
		if mode == "zookeeper" {
			configPath = filepath.Join(installDir, "config/server.properties")
		}
		if err := d.createSystemdService(ctx, "root", installDir, configPath, t); err != nil {
			return logs.String(), err
		}
		log("Kafka systemd service created")
	}

	// 9. Start Service
	if _, _, err = d.exec.RunSudo(ctx, "systemctl", "daemon-reload"); err != nil {
		return logs.String(), fmt.Errorf("failed to reload systemd: %w", err)
	}
	if mode == "zookeeper" && roleHasZooKeeper(role) {
		if _, _, err = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "zookeeper"); err != nil {
			return logs.String(), fmt.Errorf("failed to start zookeeper service: %w", err)
		}
		log("ZooKeeper service started successfully")
	}
	if mode == "kraft" || roleHasBroker(role) {
		if _, _, err = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "kafka"); err != nil {
			return logs.String(), fmt.Errorf("failed to start kafka service: %w", err)
		}
		log("Kafka service started successfully")
	}

	// 10. Post-Deployment Validation
	if err := d.validateDeployment(ctx, t, installDir, &logs); err != nil {
		return logs.String(), fmt.Errorf("deployment validation failed: %w", err)
	}
	log("All deployment validations passed ✓")

	return logs.String(), nil
}

func (d *Deployer) Upgrade(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
	}

	installDir := firstNonEmpty(t.Parameters["kafka_install_dir"], "/opt/tantor/kafka")
	dataDir := firstNonEmpty(t.Parameters["kafka_data_dir"], filepath.Join(installDir, "data"))
	targetVersion := firstNonEmpty(t.Parameters["target_version"], t.Parameters["version"])
	mode := deploymentMode(t)
	role := t.Parameters["role"]

	if targetVersion == "" {
		return logs.String(), fmt.Errorf("target Kafka version is required")
	}

	parcelDir := firstNonEmpty(t.Parameters["parcel_dir"], defaultKafkaParcelDir)
	activeLink := filepath.Join(parcelDir, "active", "kafka")
	parcelTarget, err := os.Readlink(activeLink)
	if err != nil {
		return logs.String(), fmt.Errorf("active Kafka parcel link %s was not found: %w", activeLink, err)
	}
	if !filepath.IsAbs(parcelTarget) {
		parcelTarget = filepath.Join(filepath.Dir(activeLink), parcelTarget)
	}
	if filepath.Base(parcelTarget) != targetVersion {
		return logs.String(), fmt.Errorf("active Kafka parcel is %s, expected %s", filepath.Base(parcelTarget), targetVersion)
	}
	if _, err := os.Stat(filepath.Join(parcelTarget, "bin", "kafka-server-start.sh")); err != nil {
		return logs.String(), fmt.Errorf("active parcel at %s does not look like a Kafka binary: %w", parcelTarget, err)
	}
	if _, err := os.Stat(installDir); err != nil {
		return logs.String(), fmt.Errorf("Kafka install directory %s was not found: %w", installDir, err)
	}

	log("Starting Kafka upgrade workflow...")
	log("Target version: %s", targetVersion)
	log("Active parcel: %s", parcelTarget)
	log("Install directory: %s", installDir)
	log("Preserving data directory: %s", dataDir)

	if mode == "kraft" || roleHasBroker(role) {
		log("Stopping Kafka service...")
		if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", "systemctl stop kafka 2>/dev/null || true"); err != nil {
			return logs.String(), fmt.Errorf("failed to stop kafka service: %w, out: %s, err: %s", err, out, errOut)
		}
	}
	if mode == "zookeeper" && roleHasZooKeeper(role) {
		log("Stopping ZooKeeper service...")
		if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", "systemctl stop zookeeper 2>/dev/null || true"); err != nil {
			return logs.String(), fmt.Errorf("failed to stop zookeeper service: %w, out: %s, err: %s", err, out, errOut)
		}
	}

	if err := d.switchKafkaBinariesFromParcel(ctx, parcelTarget, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	log("Kafka software switched to parcel %s", targetVersion)
	if err := d.fixKafkaRuntimePermissions(ctx, installDir, &logs); err != nil {
		return logs.String(), err
	}

	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), fmt.Errorf("failed to regenerate Kafka config: %w", err)
	}
	log("Kafka configs regenerated with existing data paths")

	if mode == "zookeeper" && roleHasZooKeeper(role) {
		if err := d.createZooKeeperSystemdService(ctx, "root", installDir, t); err != nil {
			return logs.String(), err
		}
	}
	if mode == "kraft" || roleHasBroker(role) {
		configPath := filepath.Join(installDir, "config/kraft/server.properties")
		if mode == "zookeeper" {
			configPath = filepath.Join(installDir, "config/server.properties")
		}
		if err := d.createSystemdService(ctx, "root", installDir, configPath, t); err != nil {
			return logs.String(), err
		}
	}

	if _, _, err := d.exec.RunSudo(ctx, "systemctl", "daemon-reload"); err != nil {
		return logs.String(), fmt.Errorf("failed to reload systemd: %w", err)
	}
	if mode == "zookeeper" && roleHasZooKeeper(role) {
		if _, _, err := d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "zookeeper"); err != nil {
			return logs.String(), fmt.Errorf("failed to start zookeeper service: %w", err)
		}
		log("ZooKeeper service restarted successfully")
	}
	if mode == "kraft" || roleHasBroker(role) {
		if _, _, err := d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "kafka"); err != nil {
			return logs.String(), fmt.Errorf("failed to start kafka service: %w", err)
		}
		log("Kafka service restarted successfully")
	}

	if err := d.validateDeployment(ctx, t, installDir, &logs); err != nil {
		return logs.String(), fmt.Errorf("upgrade validation failed: %w", err)
	}
	log("Kafka upgrade validations passed")
	return logs.String(), nil
}

func (d *Deployer) isSELinuxEnabled(ctx context.Context) bool {
	out, _, err := d.exec.Run(ctx, "getenforce")
	if err != nil {
		return false
	}
	out = strings.TrimSpace(out)
	return out == "Enforcing" || out == "Permissive"
}

func (d *Deployer) validateDeployment(ctx context.Context, t *api.Task, installDir string, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	if err := d.client.ReportTaskResult(&api.TaskResult{
		TaskID: t.TaskID,
		HostID: d.cfg.Agent.HostID,
		Status: "VALIDATING",
	}); err != nil {
		log("Warning: Failed to report VALIDATING status: %v", err)
	}

	if deploymentMode(t) == "zookeeper" {
		return d.validateZooKeeperDeployment(ctx, t, logs)
	}
	return d.validateKRaftDeployment(ctx, t, installDir, logs)
}

func (d *Deployer) validateKRaftDeployment(ctx context.Context, t *api.Task, installDir string, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	role := t.Parameters["role"]
	listenerPort := firstNonEmpty(t.Parameters["listener_port"], "9092")
	controllerPort := firstNonEmpty(t.Parameters["controller_port"], "9093")

	log("Validation [1/6]: Checking Kafka process...")
	if pid, ok := d.waitForProcess(ctx, "kafka.Kafka", 10, 3*time.Second); ok {
		log("  Kafka process detected (PID: %s)", pid)
	} else {
		return fmt.Errorf("Kafka process not found after 30s")
	}

	log("Validation [2/6]: Checking systemd service status...")
	out, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", "kafka")
	if err != nil || strings.TrimSpace(out) != "active" {
		return d.serviceInactiveError(ctx, "kafka", out)
	}
	log("  kafka.service is active")

	log("Validation [3/6]: Checking KRaft metadata...")
	logDirs := firstNonEmpty(t.Parameters["log_dirs"], filepath.Join(t.Parameters["kafka_data_dir"], "kafka-logs"))
	if logDirs == "" {
		logDirs = filepath.Join(installDir, "data", "kafka-logs")
	}
	if _, err := os.Stat(filepath.Join(logDirs, "meta.properties")); err != nil {
		return fmt.Errorf("KRaft meta.properties not found in %s", logDirs)
	}
	log("  KRaft meta.properties exists")

	if roleHasBroker(role) || role == "" {
		log("Validation [4/6]: Checking broker port %s...", listenerPort)
		if err := d.waitForListeningPort(ctx, listenerPort, 10, 3*time.Second); err != nil {
			return fmt.Errorf("broker port %s not listening after 30s", listenerPort)
		}
		log("  Broker listening on port %s", listenerPort)
	} else {
		log("Validation [4/6]: Checking controller port %s...", controllerPort)
		if err := d.waitForListeningPort(ctx, controllerPort, 10, 3*time.Second); err != nil {
			return fmt.Errorf("controller port %s not listening after 30s", controllerPort)
		}
		log("  Controller listening on port %s", controllerPort)
	}

	if roleHasBroker(role) || role == "" {
		d.validateJMX(ctx, logs, "5/6", "6/6")
	} else {
		log("Validation [5/6]: Skipping broker JMX check for controller-only node")
		log("Validation [6/6]: Controller-only node validation complete")
	}
	return nil
}

func (d *Deployer) validateZooKeeperDeployment(ctx context.Context, t *api.Task, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	role := t.Parameters["role"]
	listenerPort := firstNonEmpty(t.Parameters["listener_port"], "9092")
	zookeeperPort := firstNonEmpty(t.Parameters["zookeeper_port"], t.Parameters["controller_port"], "2181")

	if roleHasZooKeeper(role) {
		log("Validation [1/6]: Checking ZooKeeper service status...")
		out, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", "zookeeper")
		if err != nil || strings.TrimSpace(out) != "active" {
			return d.serviceInactiveError(ctx, "zookeeper", out)
		}
		log("  zookeeper.service is active")

		log("Validation [2/6]: Checking ZooKeeper port %s...", zookeeperPort)
		if err := d.waitForListeningPort(ctx, zookeeperPort, 10, 3*time.Second); err != nil {
			return fmt.Errorf("ZooKeeper port %s not listening after 30s", zookeeperPort)
		}
		log("  ZooKeeper listening on port %s", zookeeperPort)
	} else {
		log("Validation [1/6]: ZooKeeper service runs on another host")
		log("Validation [2/6]: Skipping local ZooKeeper port check")
	}

	if !roleHasBroker(role) {
		log("Validation [3/6]: ZooKeeper-only node validation complete")
		return nil
	}

	log("Validation [3/6]: Checking Kafka process...")
	if pid, ok := d.waitForProcess(ctx, "kafka.Kafka", 10, 3*time.Second); ok {
		log("  Kafka process detected (PID: %s)", pid)
	} else {
		return fmt.Errorf("Kafka process not found after 30s")
	}

	log("Validation [4/6]: Checking kafka.service status...")
	out, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", "kafka")
	if err != nil || strings.TrimSpace(out) != "active" {
		return d.serviceInactiveError(ctx, "kafka", out)
	}
	log("  kafka.service is active")

	log("Validation [5/6]: Checking broker port %s...", listenerPort)
	if err := d.waitForListeningPort(ctx, listenerPort, 10, 3*time.Second); err != nil {
		return fmt.Errorf("broker port %s not listening after 30s", listenerPort)
	}
	log("  Broker listening on port %s", listenerPort)

	d.validateJMX(ctx, logs, "6/6", "6/6")
	return nil
}

func (d *Deployer) validateDeploymentLegacy(ctx context.Context, t *api.Task, installDir string, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	listenerPort := t.Parameters["listener_port"]
	if listenerPort == "" {
		listenerPort = "9092"
	}
	jmxMetricsPort := "7071"

	// Report VALIDATING status
	if err := d.client.ReportTaskResult(&api.TaskResult{
		TaskID: t.TaskID,
		HostID: d.cfg.Agent.HostID,
		Status: "VALIDATING",
	}); err != nil {
		log("Warning: Failed to report VALIDATING status: %v", err)
	}

	log("Validation [1/6]: Checking Kafka process...")
	for i := 0; i < 10; i++ {
		out, _, _ := d.exec.Run(ctx, "bash", "-c", "pgrep -f 'kafka.Kafka'")
		if strings.TrimSpace(out) != "" {
			log("  ✓ Kafka process detected (PID: %s)", strings.TrimSpace(out))
			goto check2
		}
		time.Sleep(3 * time.Second)
	}
	return fmt.Errorf("Kafka process not found after 30s")

check2:
	log("Validation [2/6]: Checking systemd service status...")
	out, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", "kafka")
	if err != nil || strings.TrimSpace(out) != "active" {
		return fmt.Errorf("kafka.service is not active: %s", out)
	}
	log("  ✓ kafka.service is active")

	log("Validation [3/6]: Checking KRaft metadata...")
	logDirs := t.Parameters["log_dirs"]
	if logDirs == "" {
		logDirs = filepath.Join(t.Parameters["kafka_data_dir"], "kafka-logs") // Fixed empty string issue below
	}
	if logDirs == "" { // Use standard default if still empty
		logDirs = filepath.Join(installDir, "data", "kafka-logs")
	}
	if _, err := os.Stat(filepath.Join(logDirs, "meta.properties")); err != nil {
		return fmt.Errorf("KRaft meta.properties not found in %s", logDirs)
	}
	log("  ✓ KRaft meta.properties exists")

	log("Validation [4/6]: Checking broker port %s...", listenerPort)
	for i := 0; i < 10; i++ {
		_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("ss -tlnp | grep :%s", listenerPort))
		if err == nil {
			log("  ✓ Broker listening on port %s", listenerPort)
			goto check5
		}
		time.Sleep(3 * time.Second)
	}
	return fmt.Errorf("broker port %s not listening after 30s", listenerPort)

check5:
	log("Validation [5/6]: Checking JMX Exporter javaagent...")
	out2, _, _ := d.exec.Run(ctx, "bash", "-c", "ps aux | grep javaagent | grep -v grep")
	if strings.Contains(out2, "jmx_prometheus_javaagent") {
		log("  ✓ JMX Prometheus Exporter attached")
	} else {
		log("  ⚠ JMX Exporter not detected in process args (non-fatal)")
	}

	log("Validation [6/6]: Checking metrics endpoint on port %s...", jmxMetricsPort)
	for i := 0; i < 5; i++ {
		_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("curl -sf http://localhost:%s/metrics | head -1", jmxMetricsPort))
		if err == nil {
			log("  ✓ Metrics endpoint responding on port %s", jmxMetricsPort)
			return nil
		}
		time.Sleep(3 * time.Second)
	}
	log("  ⚠ Metrics endpoint not responding on port %s (non-fatal — JMX jar may be missing)", jmxMetricsPort)

	return nil
}

func (d *Deployer) generateConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	if deploymentMode(t) == "zookeeper" {
		return d.generateZooKeeperConfigs(ctx, t, installDir, dataDir)
	}
	return d.generateKRaftConfigs(ctx, t, installDir, dataDir)
}

func (d *Deployer) generateKRaftConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	nodeId := firstNonEmpty(t.Parameters["node_id"], "1")
	hostname := getLocalIP()
	controllerPort := firstNonEmpty(t.Parameters["controller_port"], "9093")
	listenerPort := firstNonEmpty(t.Parameters["listener_port"], "9092")
	quorumVoters := firstNonEmpty(t.Parameters["quorum_voters"], fmt.Sprintf("%s@%s:%s", nodeId, hostname, controllerPort))
	role := normalizeKRaftRole(t.Parameters["role"])
	logDirs := firstNonEmpty(t.Parameters["log_dirs"], filepath.Join(dataDir, "kafka-logs"))
	numPartitions := firstNonEmpty(t.Parameters["num_partitions"], "1")
	repFactor := firstNonEmpty(t.Parameters["replication_factor"], "1")

	listeners := "PLAINTEXT://" + hostname + ":" + listenerPort
	advertisedListeners := "PLAINTEXT://" + hostname + ":" + listenerPort
	if roleHasController(t.Parameters["role"]) {
		controllerListener := "CONTROLLER://" + hostname + ":" + controllerPort
		if roleHasBroker(t.Parameters["role"]) || t.Parameters["role"] == "" {
			listeners += "," + controllerListener
		} else {
			listeners = controllerListener
			advertisedListeners = ""
		}
	}

	props := struct {
		NodeId              string
		QuorumVoters        string
		LogDirs             string
		Role                string
		Listeners           string
		AdvertisedListeners string
		NumPartitions       string
		RepFactor           string
	}{
		NodeId:              nodeId,
		QuorumVoters:        quorumVoters,
		LogDirs:             logDirs,
		Role:                role,
		Listeners:           listeners,
		AdvertisedListeners: advertisedListeners,
		NumPartitions:       numPartitions,
		RepFactor:           repFactor,
	}

	return d.writeTemplateToSudoFile(ctx, ServerPropertiesTemplate, props, filepath.Join(installDir, "config/kraft/server.properties"))
}

func (d *Deployer) generateZooKeeperConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	role := t.Parameters["role"]
	nodeId := firstNonEmpty(t.Parameters["node_id"], "1")
	hostname := getLocalIP()
	listenerPort := firstNonEmpty(t.Parameters["listener_port"], "9092")
	zookeeperPort := firstNonEmpty(t.Parameters["zookeeper_port"], t.Parameters["controller_port"], "2181")
	zookeeperConnect := firstNonEmpty(t.Parameters["zookeeper_connect"], hostname+":"+zookeeperPort)
	logDirs := firstNonEmpty(t.Parameters["log_dirs"], filepath.Join(dataDir, "kafka-logs"))
	numPartitions := firstNonEmpty(t.Parameters["num_partitions"], "1")
	repFactor := firstNonEmpty(t.Parameters["replication_factor"], "1")

	if roleHasZooKeeper(role) {
		props := struct {
			DataDir    string
			ClientPort string
			Servers    string
		}{
			DataDir:    filepath.Join(dataDir, "zookeeper"),
			ClientPort: zookeeperPort,
			Servers:    t.Parameters["zookeeper_servers"],
		}
		if err := d.ensureWritableDir(ctx, props.DataDir); err != nil {
			return err
		}
		if strings.TrimSpace(props.Servers) != "" {
			if err := os.WriteFile(filepath.Join(props.DataDir, "myid"), []byte(nodeId+"\n"), 0644); err != nil {
				return fmt.Errorf("failed to write ZooKeeper myid: %w", err)
			}
		}
		if err := d.writeTemplateToSudoFile(ctx, ZooKeeperPropertiesTemplate, props, filepath.Join(installDir, "config/zookeeper.properties")); err != nil {
			return err
		}
	}

	if roleHasBroker(role) {
		props := struct {
			NodeId           string
			Hostname         string
			ListenerPort     string
			ZooKeeperConnect string
			LogDirs          string
			NumPartitions    string
			RepFactor        string
		}{
			NodeId:           nodeId,
			Hostname:         hostname,
			ListenerPort:     listenerPort,
			ZooKeeperConnect: zookeeperConnect,
			LogDirs:          logDirs,
			NumPartitions:    numPartitions,
			RepFactor:        repFactor,
		}
		if err := d.writeTemplateToSudoFile(ctx, ZooKeeperBrokerPropertiesTemplate, props, filepath.Join(installDir, "config/server.properties")); err != nil {
			return err
		}
	}

	return nil
}

func (d *Deployer) UpdateConfig(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	installDir := t.Parameters["kafka_install_dir"]
	if installDir == "" {
		installDir = "C:\\opt\\tantor\\kafka"
	}
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = filepath.Join(installDir, "data")
	}

	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	logs.WriteString("Configs updated successfully\n")

	// Restart if requested
	if t.Parameters["restart"] == "true" {
		_, _, err := d.exec.RunSudo(ctx, "systemctl", "restart", "kafka")
		if err != nil {
			return logs.String(), fmt.Errorf("failed to restart kafka: %w", err)
		}
		logs.WriteString("Kafka service restarted\n")
	}

	return logs.String(), nil
}

func (d *Deployer) createSystemdService(ctx context.Context, user, installDir, configPath string, t *api.Task) error {
	// Find Java Home
	out, _, _ := d.exec.Run(ctx, "bash", "-c", "dirname $(dirname $(readlink -f $(which java)))")
	javaHome := strings.TrimSpace(out)
	if javaHome == "" || javaHome == "." {
		javaHome = "/usr" // fallback
	}

	heapSize := t.Parameters["heap_size"]
	if heapSize == "" {
		heapSize = "1G"
	}

	jmxPort := t.Parameters["jmx_port"]
	jmxJarPath := filepath.Join(installDir, "jmx", "jmx_prometheus_javaagent.jar")
	jmxConfigPath := filepath.Join(installDir, "jmx", "jmx_config.yml")
	jmxAgentPath := ""
	if isUsableJmxAgent(jmxJarPath) {
		jmxAgentPath = jmxJarPath
	}

	props := struct {
		User          string
		Group         string
		JavaHome      string
		InstallDir    string
		ConfigPath    string
		HeapSize      string
		JmxPort       string
		JmxAgentPath  string
		JmxConfigPath string
	}{
		User:          user,
		Group:         user,
		JavaHome:      javaHome,
		InstallDir:    installDir,
		ConfigPath:    configPath,
		HeapSize:      heapSize,
		JmxPort:       jmxPort,
		JmxAgentPath:  jmxAgentPath,
		JmxConfigPath: jmxConfigPath,
	}

	return d.writeTemplateToSudoFile(ctx, SystemdTemplate, props, "/etc/systemd/system/kafka.service")
}

func isUsableJmxAgent(path string) bool {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() || info.Size() < 1024 {
		return false
	}

	reader, err := zip.OpenReader(path)
	if err != nil {
		return false
	}
	defer reader.Close()

	for _, file := range reader.File {
		if strings.EqualFold(file.Name, "META-INF/MANIFEST.MF") {
			return true
		}
	}
	return false
}

func (d *Deployer) serviceInactiveError(ctx context.Context, serviceName, state string) error {
	state = strings.TrimSpace(state)
	if state == "" {
		state = "unknown"
	}

	statusOut, statusErr, _ := d.exec.RunSudo(ctx, "systemctl", "status", serviceName, "--no-pager", "-l")
	journalOut, journalErr, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceName, "-n", "80", "--no-pager")

	var details strings.Builder
	if trimmed := strings.TrimSpace(statusOut); trimmed != "" {
		details.WriteString("\nSystemd status:\n")
		details.WriteString(trimmed)
	}
	if trimmed := strings.TrimSpace(statusErr); trimmed != "" {
		details.WriteString("\nSystemd status stderr:\n")
		details.WriteString(trimmed)
	}
	if trimmed := strings.TrimSpace(journalOut); trimmed != "" {
		details.WriteString("\nRecent journal:\n")
		details.WriteString(trimmed)
	}
	if trimmed := strings.TrimSpace(journalErr); trimmed != "" {
		details.WriteString("\nJournal stderr:\n")
		details.WriteString(trimmed)
	}

	return fmt.Errorf("%s.service is not active: %s%s", serviceName, state, details.String())
}

func (d *Deployer) createZooKeeperSystemdService(ctx context.Context, user, installDir string, t *api.Task) error {
	out, _, _ := d.exec.Run(ctx, "bash", "-c", "dirname $(dirname $(readlink -f $(which java)))")
	javaHome := strings.TrimSpace(out)
	if javaHome == "" || javaHome == "." {
		javaHome = "/usr"
	}

	heapSize := firstNonEmpty(t.Parameters["zookeeper_heap_size"], t.Parameters["heap_size"], "1G")

	props := struct {
		User       string
		Group      string
		JavaHome   string
		InstallDir string
		HeapSize   string
	}{
		User:       user,
		Group:      user,
		JavaHome:   javaHome,
		InstallDir: installDir,
		HeapSize:   heapSize,
	}

	return d.writeTemplateToSudoFile(ctx, ZooKeeperSystemdTemplate, props, "/etc/systemd/system/zookeeper.service")
}

func (d *Deployer) writeTemplateToSudoFile(ctx context.Context, tmplStr string, data interface{}, dest string) error {
	tmpl, err := template.New("tmpl").Parse(tmplStr)
	if err != nil {
		return err
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		return err
	}

	destDir := filepath.Dir(dest)
	if err := os.MkdirAll(destDir, 0755); err != nil {
		if _, _, sudoErr := d.exec.RunSudo(ctx, "mkdir", "-p", destDir); sudoErr != nil {
			return fmt.Errorf("failed to create dir %s: %w", destDir, err)
		}
	}

	// Strip CRLF for Linux compatibility
	content := bytes.ReplaceAll(buf.Bytes(), []byte("\r\n"), []byte("\n"))

	if err := os.WriteFile(dest, content, 0644); err != nil {
		tmpDir := d.cfg.Paths.ArtifactsDir
		if tmpDir == "" {
			tmpDir = os.TempDir()
		}
		if mkErr := os.MkdirAll(tmpDir, 0755); mkErr != nil {
			return fmt.Errorf("failed to create template temp dir %s: %w", tmpDir, mkErr)
		}

		tmpPath := filepath.Join(tmpDir, fmt.Sprintf("tantor-template-%d.tmp", time.Now().UnixNano()))
		if tmpErr := os.WriteFile(tmpPath, content, 0644); tmpErr != nil {
			return fmt.Errorf("failed to write template to %s: %w", dest, err)
		}
		defer os.Remove(tmpPath)

		if _, _, sudoErr := d.exec.RunSudo(ctx, "cp", tmpPath, dest); sudoErr != nil {
			return fmt.Errorf("failed to write template to %s: %w", dest, sudoErr)
		}
		_, _, _ = d.exec.RunSudo(ctx, "chmod", "0644", dest)
	}

	return nil
}

func (d *Deployer) ensureWritableDir(ctx context.Context, dir string) error {
	if dir == "" {
		return nil
	}

	createdWithoutSudo := false
	if err := os.MkdirAll(dir, 0755); err == nil {
		createdWithoutSudo = true
	}
	if createdWithoutSudo && d.canWriteToDir(dir) {
		return nil
	}

	if _, _, err := d.exec.RunSudo(ctx, "mkdir", "-p", dir); err != nil {
		return fmt.Errorf("failed to create directory %s: %w", dir, err)
	}
	owner := fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid())
	if _, _, err := d.exec.RunSudo(ctx, "chown", "-R", owner, dir); err != nil {
		return fmt.Errorf("failed to grant agent ownership of %s: %w", dir, err)
	}
	return nil
}

func (d *Deployer) canWriteToDir(dir string) bool {
	probe := filepath.Join(dir, fmt.Sprintf(".tantor-write-test-%d", time.Now().UnixNano()))
	if err := os.WriteFile(probe, []byte("ok"), 0600); err != nil {
		return false
	}
	_ = os.Remove(probe)
	return true
}

func (d *Deployer) fixKafkaRuntimePermissions(ctx context.Context, installDir string, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	binDir := filepath.Join(installDir, "bin")
	script := fmt.Sprintf(`
set -e
test -d %s
chmod -R a+rX %s
find %s -type f -name '*.sh' -exec chmod a+x {} +
`,
		shellQuote(binDir),
		shellQuote(binDir),
		shellQuote(binDir),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return fmt.Errorf("failed to fix Kafka executable permissions: %w, out: %s, err: %s", err, out, errOut)
	}
	log("Kafka executable permissions verified")

	if d.isSELinuxEnabled(ctx) {
		log("SELinux detected - relabeling Kafka files...")
		if _, _, err := d.exec.RunSudo(ctx, "restorecon", "-Rv", installDir); err != nil {
			log("Warning: restorecon failed (may not be RHEL): %v", err)
		}
		if _, _, err := d.exec.RunSudo(ctx, "chcon", "-R", "-t", "bin_t", binDir); err != nil {
			log("Warning: chcon failed for Kafka bin directory: %v", err)
		}
	}
	return nil
}

func (d *Deployer) switchKafkaBinariesFromParcel(ctx context.Context, parcelTarget, installDir, dataDir string) error {
	owner := fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid())
	script := fmt.Sprintf(`
set -e
test -d %s
test -d %s
mkdir -p %s
for item in bin config libs licenses site-docs LICENSE NOTICE; do
  rm -rf %s/"$item"
done
cp -a %s/. %s/
mkdir -p %s
for item in bin config libs licenses site-docs LICENSE NOTICE; do
  if [ -e %s/"$item" ]; then
    chmod -R a+rX %s/"$item"
    chown -R %s %s/"$item"
    if [ "$item" = "bin" ]; then
      find %s/"$item" -type f -name '*.sh' -exec chmod a+x {} + || true
    fi
  fi
done
`,
		shellQuote(parcelTarget),
		shellQuote(installDir),
		shellQuote(installDir),
		shellQuote(installDir),
		shellQuote(parcelTarget),
		shellQuote(installDir),
		shellQuote(dataDir),
		shellQuote(installDir),
		shellQuote(installDir),
		shellQuote(owner),
		shellQuote(installDir),
		shellQuote(installDir),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return fmt.Errorf("failed to switch Kafka binaries from active parcel: %w, out: %s, err: %s", err, out, errOut)
	}
	return nil
}

func (d *Deployer) installFromActiveParcel(ctx context.Context, t *api.Task, installDir string, logs *strings.Builder) (bool, error) {
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
	}

	if !strings.EqualFold(t.Parameters["use_active_parcel"], "true") {
		return false, nil
	}

	parcelDir := firstNonEmpty(t.Parameters["parcel_dir"], defaultKafkaParcelDir)
	activeLink := filepath.Join(parcelDir, "active", "kafka")
	target, err := os.Readlink(activeLink)
	if err != nil {
		log("Active Kafka parcel was requested, but %s is not available; downloading artifact instead", activeLink)
		return false, nil
	}
	if !filepath.IsAbs(target) {
		target = filepath.Join(filepath.Dir(activeLink), target)
	}

	expectedVersion := strings.TrimSpace(t.Parameters["version"])
	if expectedVersion != "" && filepath.Base(target) != expectedVersion {
		log("Active Kafka parcel version %s does not match requested version %s; downloading artifact instead", filepath.Base(target), expectedVersion)
		return false, nil
	}

	owner := fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid())
	script := fmt.Sprintf(
		"set -e; test -d %s; rm -rf %s; mkdir -p %s %s; cp -a %s/. %s/; chmod -R a+rX %s; chown -R %s %s",
		shellQuote(target),
		shellQuote(installDir),
		shellQuote(filepath.Dir(installDir)),
		shellQuote(installDir),
		shellQuote(activeLink),
		shellQuote(installDir),
		shellQuote(installDir),
		shellQuote(owner),
		shellQuote(installDir),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return false, fmt.Errorf("failed to install Kafka from active parcel: %w, out: %s, err: %s", err, out, errOut)
	}

	log("Installed Kafka from active parcel %s to %s", target, installDir)
	return true, nil
}

func (d *Deployer) Clean(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Info(formatted)
	}

	installDir := t.Parameters["kafka_install_dir"]
	dataDir := t.Parameters["kafka_data_dir"]
	logDirs := t.Parameters["log_dirs"]
	listenerPort := t.Parameters["listener_port"]
	controllerPort := t.Parameters["controller_port"]
	zookeeperPort := t.Parameters["zookeeper_port"]
	mode := deploymentMode(t)

	if installDir == "" {
		installDir = "/opt/tantor/kafka"
	}
	if dataDir == "" {
		dataDir = filepath.Join(installDir, "data")
	}
	if logDirs == "" {
		logDirs = filepath.Join(dataDir, "kafka-logs")
	}
	if listenerPort == "" {
		listenerPort = "9092"
	}
	if controllerPort == "" {
		if mode == "zookeeper" {
			controllerPort = firstNonEmpty(zookeeperPort, "2181")
		} else {
			controllerPort = "9093"
		}
	}
	if zookeeperPort == "" {
		if mode == "zookeeper" {
			zookeeperPort = controllerPort
		} else {
			zookeeperPort = "2181"
		}
	}

	log("Starting Kafka cleanup process...")

	// 1. Stop and remove broker systemd units before killing processes, otherwise
	// Restart=on-failure units can respawn Kafka immediately after fuser/pkill.
	if err := d.cleanupKafkaSystemdUnits(ctx, &logs); err != nil {
		return logs.String(), err
	}

	// 2. Kill remaining broker/JMX processes on known deployment ports.
	log("Terminating processes on port %s, %s, 7071...", listenerPort, controllerPort)
	d.killKafkaRuntimeProcesses(ctx, listenerPort, controllerPort, &logs)
	time.Sleep(2 * time.Second)

	// 3. Remove files
	log("Purging directories: %s, %s, %s", installDir, dataDir, logDirs)
	d.exec.RunSudo(ctx, "rm", "-rf", installDir)
	d.exec.RunSudo(ctx, "rm", "-rf", dataDir)
	d.exec.RunSudo(ctx, "rm", "-rf", logDirs)

	// 4. Validate ports are free
	log("Validating ports are free...")
	out, _, _ := d.exec.RunSudo(ctx, "ss", "-tlnp")
	zooPortStillUsed := mode == "zookeeper" && strings.Contains(out, ":"+zookeeperPort+" ")
	if strings.Contains(out, ":"+listenerPort+" ") || strings.Contains(out, ":"+controllerPort+" ") || zooPortStillUsed || strings.Contains(out, ":7071 ") {
		return logs.String(), fmt.Errorf("Ports are still in use after cleanup")
	}

	log("Cleanup completed successfully.")
	return logs.String(), nil
}

func (d *Deployer) cleanupKafkaSystemdUnits(ctx context.Context, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Info(formatted)
	}

	log("Stopping and removing Kafka and ZooKeeper systemd units...")
	script := `
set +e
units="$(systemctl list-units --type=service --all --no-legend 'kafka.service' 'kafka-managed-*.service' 'kafka-test-*.service' 'zookeeper.service' 'zookeeper-managed-*.service' 2>/dev/null | awk '{print $1}' | sort -u)"
files="$(find /etc/systemd/system -maxdepth 1 \( -name 'kafka.service' -o -name 'kafka-managed-*.service' -o -name 'kafka-test-*.service' -o -name 'zookeeper.service' -o -name 'zookeeper-managed-*.service' \) -printf '%f\n' 2>/dev/null | sort -u)"
for unit in $units $files; do
  [ -n "$unit" ] || continue
  systemctl stop "$unit" 2>/dev/null || true
  systemctl disable "$unit" 2>/dev/null || true
  systemctl reset-failed "$unit" 2>/dev/null || true
done
find /etc/systemd/system -maxdepth 1 \( -name 'kafka.service' -o -name 'kafka-managed-*.service' -o -name 'kafka-test-*.service' -o -name 'zookeeper.service' -o -name 'zookeeper-managed-*.service' \) -delete 2>/dev/null || true
systemctl daemon-reload 2>/dev/null || true
`
	out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script)
	if out != "" {
		log("systemd cleanup output: %s", out)
	}
	if errOut != "" {
		log("systemd cleanup warnings: %s", errOut)
	}
	if err != nil {
		return fmt.Errorf("failed to clean Kafka systemd units: %w", err)
	}
	return nil
}

func (d *Deployer) killKafkaRuntimeProcesses(ctx context.Context, listenerPort, controllerPort string, logs *strings.Builder) {
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Info(formatted)
	}

	ports := []string{listenerPort, controllerPort, "7071"}
	for _, port := range ports {
		if strings.TrimSpace(port) == "" {
			continue
		}
		if out, errOut, err := d.exec.RunSudo(ctx, "fuser", "-k", port+"/tcp"); err != nil {
			if errOut != "" {
				log("fuser warning for port %s: %s", port, errOut)
			}
		} else if out != "" {
			log("Killed process(es) on port %s: %s", port, out)
		}
	}

	_, _, _ = d.exec.RunSudo(ctx, "pkill", "-f", "kafka.Kafka")
	_, _, _ = d.exec.RunSudo(ctx, "pkill", "-f", "jmx_prometheus_javaagent")
	_, _, _ = d.exec.RunSudo(ctx, "pkill", "-f", "QuorumPeerMain")
	_, _, _ = d.exec.RunSudo(ctx, "pkill", "-f", "zookeeper-server-start")
}

func (d *Deployer) waitForProcess(ctx context.Context, pattern string, attempts int, delay time.Duration) (string, bool) {
	for i := 0; i < attempts; i++ {
		out, _, _ := d.exec.Run(ctx, "bash", "-c", "pgrep -f '"+pattern+"'")
		if strings.TrimSpace(out) != "" {
			return strings.TrimSpace(out), true
		}
		time.Sleep(delay)
	}
	return "", false
}

func (d *Deployer) waitForListeningPort(ctx context.Context, port string, attempts int, delay time.Duration) error {
	for i := 0; i < attempts; i++ {
		_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("ss -tlnp | grep :%s", port))
		if err == nil {
			return nil
		}
		time.Sleep(delay)
	}
	return fmt.Errorf("port %s not listening", port)
}

func (d *Deployer) validateJMX(ctx context.Context, logs *strings.Builder, processStep, metricsStep string) {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	jmxMetricsPort := "7071"
	log("Validation [%s]: Checking JMX Exporter javaagent...", processStep)
	out, _, _ := d.exec.Run(ctx, "bash", "-c", "ps aux | grep javaagent | grep -v grep")
	if strings.Contains(out, "jmx_prometheus_javaagent") {
		log("  JMX Prometheus Exporter attached")
	} else {
		log("  JMX Exporter not detected in process args (non-fatal)")
	}

	log("Validation [%s]: Checking metrics endpoint on port %s...", metricsStep, jmxMetricsPort)
	for i := 0; i < 5; i++ {
		_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("curl -sf http://localhost:%s/metrics | head -1", jmxMetricsPort))
		if err == nil {
			log("  Metrics endpoint responding on port %s", jmxMetricsPort)
			return
		}
		time.Sleep(3 * time.Second)
	}
	log("  Metrics endpoint not responding on port %s (non-fatal - JMX jar may be missing)", jmxMetricsPort)
}

func deploymentMode(t *api.Task) string {
	if strings.EqualFold(t.Parameters["mode"], "zookeeper") {
		return "zookeeper"
	}
	return "kraft"
}

func normalizeKRaftRole(role string) string {
	switch role {
	case "broker":
		return "broker"
	case "controller":
		return "controller"
	default:
		return "broker,controller"
	}
}

func roleHasBroker(role string) bool {
	return role == "" || role == "broker" || role == "broker_controller" || role == "broker_zookeeper"
}

func roleHasController(role string) bool {
	return role == "" || role == "controller" || role == "broker_controller"
}

func roleHasZooKeeper(role string) bool {
	return role == "zookeeper" || role == "broker_zookeeper"
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\"'\"'") + "'"
}

// getLocalIP dynamically fetches the first non-loopback IPv4 address of the host.
// If none is found, it falls back to the OS hostname.
func getLocalIP() string {
	addrs, err := net.InterfaceAddrs()
	if err == nil {
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip != nil && !ip.IsLoopback() && ip.To4() != nil {
				return ip.String()
			}
		}
	}
	h, _ := os.Hostname()
	return h
}
