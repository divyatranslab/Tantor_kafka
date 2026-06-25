package kafka

import (
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

type Deployer struct {
	cfg    *config.Config
	client *client.APIClient
	exec   executor.Executor
}

type kafkaRolePaths struct {
	LogDirs           string
	MetadataLogDir    string
	AppLogDir         string
	MetaPropertiesDir string
}

type kafkaInstallPaths struct {
	BaseDir      string
	VersionedDir string
	ActiveDir    string
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

	installPaths := resolveKafkaInstallPaths(t)
	installDir := installPaths.VersionedDir
	activeInstallDir := installPaths.ActiveDir
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}
	paths := resolveKafkaRolePaths(t, installDir, dataDir)

	log("Starting cross-platform Kafka Deployment Workflow...")
	log("Kafka install base directory: %s", installPaths.BaseDir)
	log("Kafka versioned binary directory: %s", installDir)
	log("Kafka active symlink: %s -> %s", activeInstallDir, installDir)
	log("Kafka data base directory: %s", dataDir)
	if paths.LogDirs != "" {
		log("Broker data directory: %s", paths.LogDirs)
	}
	if paths.MetadataLogDir != "" {
		log("KRaft metadata directory: %s", paths.MetadataLogDir)
	}
	if paths.AppLogDir != "" {
		log("Kafka application log directory: %s", paths.AppLogDir)
	}

	// 1. Create directories
	os.MkdirAll(installPaths.BaseDir, 0755)
	os.MkdirAll(installDir, 0755)
	os.MkdirAll(dataDir, 0755)
	os.MkdirAll(d.cfg.Paths.ArtifactsDir, 0755)
	for _, dir := range []string{paths.LogDirs, paths.MetadataLogDir, paths.AppLogDir} {
		if dir != "" {
			os.MkdirAll(dir, 0755)
		}
	}

	// 2. Download TAR
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
			os.WriteFile(dest, data, mode)
		}
		return nil
	})
	os.RemoveAll(tmpExtractDir)
	log("Artifact extracted to %s", installDir)

	if err := d.ensureActiveSymlink(ctx, activeInstallDir, installDir); err != nil {
		return logs.String(), err
	}
	log("Kafka active symlink updated: %s -> %s", activeInstallDir, installDir)

	// 5.5 Fix SELinux contexts for extracted files (RHEL/CentOS)
	if d.isSELinuxEnabled(ctx) {
		log("SELinux detected — relabeling Kafka files...")
		_, _, err := d.exec.RunSudo(ctx, "restorecon", "-Rv", installDir)
		if err != nil {
			log("Warning: restorecon failed (may not be RHEL): %v", err)
		}
		d.exec.RunSudo(ctx, "chcon", "-R", "-t", "bin_t", filepath.Join(installDir, "bin"))
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
			os.Remove(jmxJarPath)
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

	// 7. Generate Configs
	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	log("Configs generated successfully")

	// 7.5 Format KRaft Storage (only on fresh deploy)
	metaPropsPath := filepath.Join(paths.MetaPropertiesDir, "meta.properties")
	if _, err := os.Stat(metaPropsPath); os.IsNotExist(err) {
		log("Fresh deployment detected — formatting KRaft storage...")
		storageScript := filepath.Join(activeInstallDir, "bin", "kafka-storage.sh")
		configPath := configPathForTask(activeInstallDir, t)

		clusterUUID := strings.TrimSpace(t.Parameters["cluster_uuid"])
		if clusterUUID == "" {
			uuidOut, _, err := d.exec.Run(ctx, storageScript, "random-uuid")
			if err != nil {
				return logs.String(), fmt.Errorf("failed to generate cluster UUID: %w", err)
			}
			clusterUUID = strings.TrimSpace(uuidOut)
			log("Generated cluster UUID: %s", clusterUUID)
		} else {
			log("Using shared cluster UUID: %s", clusterUUID)
		}

		_, _, err = d.exec.Run(ctx, storageScript, "format", "-t", clusterUUID, "-c", configPath)
		if err != nil {
			return logs.String(), fmt.Errorf("failed to format KRaft storage: %w", err)
		}
		log("KRaft storage formatted successfully")
	} else {
		log("Existing KRaft metadata found — skipping format (safe re-deploy)")
	}

	// 8. Systemd Service
	serviceName := serviceNameForTask(t)
	if err := d.createSystemdService(ctx, "root", activeInstallDir, t); err != nil {
		return logs.String(), err
	}
	log("Systemd service created")

	// 9. Start Service
	_, _, err = d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	if err == nil {
		_, _, err = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", serviceName)
	}
	if err != nil {
		return logs.String(), fmt.Errorf("failed to start service: %w", err)
	}
	log("Kafka service %s started successfully", serviceName)

	// 10. Post-Deployment Validation
	if err := d.validateDeployment(ctx, t, activeInstallDir, &logs); err != nil {
		return logs.String(), fmt.Errorf("deployment validation failed: %w", err)
	}
	log("All deployment validations passed ✓")

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
	serviceName := serviceNameForTask(t)
	out, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", serviceName)
	if err != nil || strings.TrimSpace(out) != "active" {
		return fmt.Errorf("%s.service is not active: %s", serviceName, out)
	}
	log("  ✓ %s.service is active", serviceName)

	log("Validation [3/6]: Checking KRaft metadata...")
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		installPaths := resolveKafkaInstallPaths(t)
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}
	paths := resolveKafkaRolePaths(t, installDir, dataDir)
	if _, err := os.Stat(filepath.Join(paths.MetaPropertiesDir, "meta.properties")); err != nil {
		return fmt.Errorf("KRaft meta.properties not found in %s", paths.MetaPropertiesDir)
	}
	log("  ✓ KRaft meta.properties exists")

	role, isBroker, isController := normalizeKRaftRole(t.Parameters["role"])
	controllerPort := t.Parameters["controller_port"]
	if controllerPort == "" {
		controllerPort = "9093"
	}

	log("Validation [4/6]: Checking service ports for %s...", role)
	if isBroker {
		for i := 0; i < 10; i++ {
			_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("ss -tlnp | grep :%s", listenerPort))
			if err == nil {
				log("  ✓ Broker listening on port %s", listenerPort)
				goto controllerPortCheck
			}
			time.Sleep(3 * time.Second)
		}
		return fmt.Errorf("broker port %s not listening after 30s", listenerPort)
	}

controllerPortCheck:
	if isController {
		for i := 0; i < 10; i++ {
			_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("ss -tlnp | grep :%s", controllerPort))
			if err == nil {
				log("  ✓ Controller listening on port %s", controllerPort)
				goto check5
			}
			time.Sleep(3 * time.Second)
		}
		return fmt.Errorf("controller port %s not listening after 30s", controllerPort)
	}

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

func normalizeKRaftRole(rawRole string) (string, bool, bool) {
	switch rawRole {
	case "broker":
		return "broker", true, false
	case "controller":
		return "controller", false, true
	case "broker_controller", "":
		return "broker,controller", true, true
	default:
		if strings.Contains(rawRole, "broker") && strings.Contains(rawRole, "controller") {
			return "broker,controller", true, true
		}
		return rawRole, strings.Contains(rawRole, "broker"), strings.Contains(rawRole, "controller")
	}
}

func defaultKafkaDataDir(baseInstallDir string) string {
	baseDir := filepath.Clean(baseInstallDir)
	if baseDir == "." || baseDir == string(filepath.Separator) || baseDir == "/opt" {
		return "/data/kafka"
	}
	return filepath.Join(baseDir, "kafka-data")
}

func resolveKafkaInstallPaths(t *api.Task) kafkaInstallPaths {
	baseDir := strings.TrimSpace(t.Parameters["kafka_install_base_dir"])
	if baseDir == "" {
		baseDir = strings.TrimSpace(t.Parameters["kafka_install_dir"])
	}
	if baseDir == "" {
		baseDir = "/opt"
	}

	baseDir = filepath.Clean(baseDir)
	version := strings.TrimSpace(t.Parameters["target_version"])
	if version == "" {
		version = strings.TrimSpace(t.Parameters["version"])
	}
	if version == "" {
		version = "unknown"
	}
	scalaVersion := strings.TrimSpace(t.Parameters["scala_version"])
	if scalaVersion == "" {
		scalaVersion = "2.13"
	}

	versionedName := kafkaVersionedDirName(scalaVersion, version)
	baseName := filepath.Base(baseDir)
	if strings.HasPrefix(baseName, "kafka_") {
		parent := filepath.Dir(baseDir)
		return kafkaInstallPaths{BaseDir: parent, VersionedDir: baseDir, ActiveDir: filepath.Join(parent, "kafka")}
	}
	if baseName == "kafka" {
		parent := filepath.Dir(baseDir)
		return kafkaInstallPaths{BaseDir: parent, VersionedDir: filepath.Join(parent, versionedName), ActiveDir: baseDir}
	}

	return kafkaInstallPaths{BaseDir: baseDir, VersionedDir: filepath.Join(baseDir, versionedName), ActiveDir: filepath.Join(baseDir, "kafka")}
}

func kafkaVersionedDirName(scalaVersion, kafkaVersion string) string {
	clean := func(value string) string {
		replacer := strings.NewReplacer("/", "-", "\\", "-", " ", "-", ":", "-", "..", "-")
		value = replacer.Replace(strings.TrimSpace(value))
		value = strings.Trim(value, ".-")
		if value == "" {
			return "unknown"
		}
		return value
	}
	return fmt.Sprintf("kafka_%s-%s", clean(scalaVersion), clean(kafkaVersion))
}

func (d *Deployer) ensureActiveSymlink(ctx context.Context, activeDir, versionedDir string) error {
	if info, err := os.Lstat(activeDir); err == nil && info.Mode()&os.ModeSymlink == 0 {
		return fmt.Errorf("%s already exists and is not a symlink; move or remove it before using production symlink layout", activeDir)
	}
	if _, _, err := d.exec.RunSudo(ctx, "ln", "-sfn", versionedDir, activeDir); err != nil {
		return fmt.Errorf("failed to create Kafka active symlink %s -> %s: %w", activeDir, versionedDir, err)
	}
	return nil
}

func resolveKafkaRolePaths(t *api.Task, installDir, dataDir string) kafkaRolePaths {
	_, isBroker, isController := normalizeKRaftRole(t.Parameters["role"])

	appLogBaseDir := strings.TrimSpace(t.Parameters["kafka_app_log_dir"])
	if appLogBaseDir == "" {
		appLogBaseDir = filepath.Join(filepath.Dir(installDir), "kafka-logs")
	}

	paths := kafkaRolePaths{}
	if isBroker {
		paths.LogDirs = strings.TrimSpace(t.Parameters["log_dirs"])
		if paths.LogDirs == "" {
			paths.LogDirs = filepath.Join(dataDir, "broker-data")
		}
		paths.MetadataLogDir = strings.TrimSpace(t.Parameters["metadata_log_dir"])
		if paths.MetadataLogDir == "" {
			paths.MetadataLogDir = filepath.Join(dataDir, "broker-metadata")
		}
		paths.AppLogDir = filepath.Join(appLogBaseDir, "kafka-broker")
	}

	if isController && !isBroker {
		paths.MetadataLogDir = strings.TrimSpace(t.Parameters["metadata_log_dir"])
		if paths.MetadataLogDir == "" {
			paths.MetadataLogDir = filepath.Join(dataDir, "controller-data", "metadata")
		}
		paths.AppLogDir = filepath.Join(appLogBaseDir, "kafka-controller")
	}

	paths.MetaPropertiesDir = paths.MetadataLogDir
	if paths.MetaPropertiesDir == "" {
		paths.MetaPropertiesDir = paths.LogDirs
	}
	if paths.MetaPropertiesDir == "" {
		paths.MetaPropertiesDir = filepath.Join(dataDir, "broker-metadata")
	}

	return paths
}

func buildKRaftListeners(hostname, listenerPort, controllerPort string, isBroker, isController bool) string {
	listeners := make([]string, 0, 2)
	if isBroker {
		listeners = append(listeners, fmt.Sprintf("PLAINTEXT://%s:%s", hostname, listenerPort))
	}
	if isController {
		listeners = append(listeners, fmt.Sprintf("CONTROLLER://%s:%s", hostname, controllerPort))
	}
	if len(listeners) == 0 {
		listeners = append(listeners, fmt.Sprintf("PLAINTEXT://%s:%s", hostname, listenerPort))
	}
	return strings.Join(listeners, ",")
}
func serviceNameForTask(t *api.Task) string {
	serviceName := strings.TrimSpace(t.Parameters["systemd_service"])
	if serviceName == "" {
		serviceName = strings.TrimSpace(t.Parameters["service_name"])
	}
	if serviceName != "" {
		return strings.TrimSuffix(serviceName, ".service")
	}

	rawRole := strings.TrimSpace(t.Parameters["service_role"])
	if rawRole == "" {
		rawRole = strings.TrimSpace(t.Parameters["role"])
	}
	switch rawRole {
	case "controller":
		return "controller"
	case "zookeeper":
		return "zookeeper"
	case "broker_controller", "broker_zookeeper":
		return "kafka"
	default:
		return "broker"
	}
}

func configPathForTask(installDir string, t *api.Task) string {
	configured := strings.TrimSpace(t.Parameters["config_path"])
	if configured != "" {
		if filepath.IsAbs(configured) {
			return configured
		}
		return filepath.Join(installDir, "config", "kraft", configured)
	}

	configured = strings.TrimSpace(t.Parameters["config_file"])
	if configured != "" {
		if filepath.IsAbs(configured) {
			return configured
		}
		if configured == "zookeeper.properties" || configured == "server.properties" {
			return filepath.Join(installDir, "config", configured)
		}
		return filepath.Join(installDir, "config", "kraft", configured)
	}

	switch serviceNameForTask(t) {
	case "controller":
		return filepath.Join(installDir, "config", "kraft", "controller.properties")
	case "broker":
		return filepath.Join(installDir, "config", "kraft", "broker.properties")
	case "zookeeper":
		return filepath.Join(installDir, "config", "zookeeper.properties")
	default:
		return filepath.Join(installDir, "config", "kraft", "server.properties")
	}
}
func (d *Deployer) generateConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	nodeId := t.Parameters["node_id"]
	if nodeId == "" {
		nodeId = "1"
	}
	hostname := getLocalIP()
	quorumVoters := t.Parameters["quorum_voters"]
	if quorumVoters == "" {
		quorumVoters = fmt.Sprintf("%s@%s:9093", nodeId, hostname)
	}

	rawRole := t.Parameters["service_role"]
	if rawRole == "" {
		rawRole = t.Parameters["role"]
	}
	role, isBroker, isController := normalizeKRaftRole(rawRole)

	listenerPort := t.Parameters["listener_port"]
	if listenerPort == "" {
		listenerPort = "9092"
	}

	controllerPort := t.Parameters["controller_port"]
	if controllerPort == "" {
		controllerPort = "9093"
	}

	listeners := buildKRaftListeners(hostname, listenerPort, controllerPort, isBroker, isController)
	advertisedListeners := ""
	if isBroker {
		advertisedListeners = fmt.Sprintf("PLAINTEXT://%s:%s", hostname, listenerPort)
	}

	paths := resolveKafkaRolePaths(t, installDir, dataDir)

	numPartitions := t.Parameters["num_partitions"]
	if numPartitions == "" {
		numPartitions = "1"
	}

	repFactor := t.Parameters["replication_factor"]
	if repFactor == "" {
		repFactor = "1"
	}

	props := struct {
		NodeId              string
		QuorumVoters        string
		Hostname            string
		LogDirs             string
		MetadataLogDir      string
		Role                string
		Listeners           string
		AdvertisedListeners string
		ListenerPort        string
		ControllerPort      string
		NumPartitions       string
		RepFactor           string
		IsBroker            bool
	}{
		NodeId:              nodeId,
		QuorumVoters:        quorumVoters,
		Hostname:            hostname,
		LogDirs:             paths.LogDirs,
		MetadataLogDir:      paths.MetadataLogDir,
		Role:                role,
		Listeners:           listeners,
		AdvertisedListeners: advertisedListeners,
		ListenerPort:        listenerPort,
		ControllerPort:      controllerPort,
		NumPartitions:       numPartitions,
		RepFactor:           repFactor,
		IsBroker:            isBroker,
	}

	return d.writeTemplateToSudoFile(ctx, ServerPropertiesTemplate, props, configPathForTask(installDir, t))
}

func (d *Deployer) UpdateConfig(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	installPaths := resolveKafkaInstallPaths(t)
	installDir := installPaths.ActiveDir
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}

	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	logs.WriteString("Configs updated successfully\n")

	// Restart if requested
	if t.Parameters["restart"] == "true" {
		_, _, err := d.exec.RunSudo(ctx, "systemctl", "restart", serviceNameForTask(t))
		if err != nil {
			return logs.String(), fmt.Errorf("failed to restart kafka: %w", err)
		}
		logs.WriteString(fmt.Sprintf("Kafka service %s restarted\n", serviceNameForTask(t)))
	}

	return logs.String(), nil
}

func (d *Deployer) Upgrade(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Info(formatted)
	}

	installPaths := resolveKafkaInstallPaths(t)
	targetVersion := strings.TrimSpace(t.Parameters["target_version"])
	if targetVersion == "" {
		targetVersion = strings.TrimSpace(t.Parameters["version"])
	}
	if targetVersion == "" {
		return logs.String(), fmt.Errorf("target Kafka version is required")
	}

	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}

	log("Starting Kafka upgrade workflow...")
	log("Target version: %s", targetVersion)
	log("Kafka install base directory: %s", installPaths.BaseDir)
	log("Target versioned binary directory: %s", installPaths.VersionedDir)
	log("Active symlink: %s", installPaths.ActiveDir)
	log("Preserving data directory: %s", dataDir)

	previousTarget, err := d.activeSymlinkTarget(ctx, installPaths.ActiveDir)
	if err != nil {
		return logs.String(), err
	}
	log("Current active Kafka binary directory: %s", previousTarget)

	if err := d.stageUpgradeBinaries(ctx, t, installPaths.VersionedDir, log); err != nil {
		return logs.String(), err
	}
	if err := d.ensureKafkaBinaryVersion(ctx, installPaths.VersionedDir, targetVersion, log); err != nil {
		return logs.String(), err
	}

	log("Stopping Kafka service...")
	d.exec.RunSudo(ctx, "systemctl", "stop", serviceNameForTask(t))

	if err := d.ensureActiveSymlink(ctx, installPaths.ActiveDir, installPaths.VersionedDir); err != nil {
		return logs.String(), err
	}
	log("Kafka software switched to version %s", targetVersion)

	if err := d.generateConfigs(ctx, t, installPaths.ActiveDir, dataDir); err != nil {
		d.rollbackUpgrade(ctx, installPaths.ActiveDir, previousTarget, &logs, t)
		return logs.String(), fmt.Errorf("failed to regenerate Kafka configs: %w", err)
	}
	log("Kafka configs regenerated with existing data paths")

	if err := d.createSystemdService(ctx, "root", installPaths.ActiveDir, t); err != nil {
		d.rollbackUpgrade(ctx, installPaths.ActiveDir, previousTarget, &logs, t)
		return logs.String(), fmt.Errorf("failed to update kafka.service: %w", err)
	}

	if _, _, err := d.exec.RunSudo(ctx, "systemctl", "daemon-reload"); err != nil {
		d.rollbackUpgrade(ctx, installPaths.ActiveDir, previousTarget, &logs, t)
		return logs.String(), fmt.Errorf("failed to reload systemd: %w", err)
	}
	if _, _, err := d.exec.RunSudo(ctx, "systemctl", "restart", serviceNameForTask(t)); err != nil {
		d.rollbackUpgrade(ctx, installPaths.ActiveDir, previousTarget, &logs, t)
		return logs.String(), fmt.Errorf("failed to restart kafka: %w", err)
	}
	log("Kafka service restarted successfully")

	if err := d.validateDeployment(ctx, t, installPaths.ActiveDir, &logs); err != nil {
		d.rollbackUpgrade(ctx, installPaths.ActiveDir, previousTarget, &logs, t)
		return logs.String(), fmt.Errorf("upgrade validation failed: %w", err)
	}
	if err := d.ensureKafkaBinaryVersion(ctx, installPaths.ActiveDir, targetVersion, log); err != nil {
		d.rollbackUpgrade(ctx, installPaths.ActiveDir, previousTarget, &logs, t)
		return logs.String(), fmt.Errorf("post-upgrade version validation failed: %w", err)
	}

	log("Kafka upgrade validations passed")
	return logs.String(), nil
}

func (d *Deployer) activeSymlinkTarget(ctx context.Context, activeDir string) (string, error) {
	info, err := os.Lstat(activeDir)
	if err != nil {
		return "", fmt.Errorf("active Kafka symlink %s does not exist; deploy the cluster with the production symlink layout before upgrading: %w", activeDir, err)
	}
	if info.Mode()&os.ModeSymlink == 0 {
		return "", fmt.Errorf("%s is not a symlink; upgrade/rollback requires the production versioned directory layout", activeDir)
	}

	out, _, err := d.exec.Run(ctx, "readlink", "-f", activeDir)
	if err == nil && strings.TrimSpace(out) != "" {
		return strings.TrimSpace(out), nil
	}

	target, err := os.Readlink(activeDir)
	if err != nil {
		return "", fmt.Errorf("failed to read active Kafka symlink %s: %w", activeDir, err)
	}
	if !filepath.IsAbs(target) {
		target = filepath.Join(filepath.Dir(activeDir), target)
	}
	return filepath.Clean(target), nil
}

func (d *Deployer) stageUpgradeBinaries(ctx context.Context, t *api.Task, targetDir string, log func(string, ...interface{})) error {
	startScript := filepath.Join(targetDir, "bin", "kafka-server-start.sh")
	if _, err := os.Stat(startScript); err == nil {
		log("Kafka target binaries already staged at %s", targetDir)
		_, _, _ = d.exec.RunSudo(ctx, "bash", "-c", fmt.Sprintf("find %s/bin -type f -name '*.sh' -exec chmod a+x {} + || true", shellQuote(targetDir)))
		return nil
	}

	parcelDir := strings.TrimSpace(t.Parameters["parcel_dir"])
	if parcelDir == "" {
		return fmt.Errorf("active parcel directory is required for upgrade; distribute and activate the target Kafka parcel first")
	}

	log("Staging Kafka binaries from active parcel: %s", parcelDir)
	script := fmt.Sprintf(
		"set -e; test -d %s; rm -rf %s; mkdir -p %s %s; cp -a %s/. %s/; chmod -R a+rX %s; find %s/bin -type f -name '*.sh' -exec chmod a+x {} + || true",
		shellQuote(parcelDir),
		shellQuote(targetDir),
		shellQuote(filepath.Dir(targetDir)),
		shellQuote(targetDir),
		shellQuote(parcelDir),
		shellQuote(targetDir),
		shellQuote(targetDir),
		shellQuote(targetDir),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return fmt.Errorf("failed to stage target Kafka binaries: %w, out: %s, err: %s", err, out, errOut)
	}
	log("Kafka target binaries staged at %s", targetDir)
	return nil
}

func (d *Deployer) ensureKafkaBinaryVersion(ctx context.Context, installDir, expectedVersion string, log func(string, ...interface{})) error {
	versionScript := filepath.Join(installDir, "bin", "kafka-topics.sh")
	out, errOut, err := d.exec.Run(ctx, versionScript, "--version")
	if err != nil {
		return fmt.Errorf("failed to read Kafka binary version from %s: %w, err: %s", versionScript, err, errOut)
	}
	actual := strings.TrimSpace(out)
	log("Kafka binary version detected at %s: %s", installDir, actual)
	if actual != expectedVersion {
		return fmt.Errorf("Kafka binary version mismatch: expected %s but found %s", expectedVersion, actual)
	}
	return nil
}

func (d *Deployer) rollbackUpgrade(ctx context.Context, activeDir, previousTarget string, logs *strings.Builder, t *api.Task) {
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Warn(formatted)
	}

	log("Upgrade failed; starting automatic rollback to %s", previousTarget)
	d.exec.RunSudo(ctx, "systemctl", "stop", serviceNameForTask(t))
	if _, _, err := d.exec.RunSudo(ctx, "ln", "-sfn", previousTarget, activeDir); err != nil {
		log("Rollback failed while restoring active symlink: %v", err)
		return
	}
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	if _, _, err := d.exec.RunSudo(ctx, "systemctl", "restart", serviceNameForTask(t)); err != nil {
		log("Rollback symlink restored, but kafka restart failed: %v", err)
		return
	}
	log("Rollback completed; Kafka active symlink restored to %s", previousTarget)
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\"'\"'") + "'"
}

func isUsableJar(path string) bool {
	data, err := os.ReadFile(path)
	return err == nil && len(data) >= 2 && data[0] == 'P' && data[1] == 'K'
}

func (d *Deployer) createSystemdService(ctx context.Context, user, installDir string, t *api.Task) error {
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
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		installPaths := resolveKafkaInstallPaths(t)
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}
	paths := resolveKafkaRolePaths(t, installDir, dataDir)

	serviceName := serviceNameForTask(t)
	jmxAgentPath := filepath.Join(installDir, "jmx", "jmx_prometheus_javaagent.jar")
	jmxConfigPath := filepath.Join(installDir, "jmx", "jmx_config.yml")
	if serviceName == "controller" || !isUsableJar(jmxAgentPath) {
		jmxPort = ""
		jmxAgentPath = ""
		jmxConfigPath = ""
	}

	props := struct {
		User          string
		Group         string
		JavaHome      string
		InstallDir    string
		HeapSize      string
		JmxPort       string
		JmxAgentPath  string
		JmxConfigPath string
		AppLogDir     string
		ConfigPath    string
	}{
		User:          user,
		Group:         user,
		JavaHome:      javaHome,
		InstallDir:    installDir,
		HeapSize:      heapSize,
		JmxPort:       jmxPort,
		JmxAgentPath:  jmxAgentPath,
		JmxConfigPath: jmxConfigPath,
		AppLogDir:     paths.AppLogDir,
		ConfigPath:    configPathForTask(installDir, t),
	}

	return d.writeTemplateToSudoFile(ctx, SystemdTemplate, props, filepath.Join("/etc/systemd/system", serviceName+".service"))
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

	if err := os.MkdirAll(filepath.Dir(dest), 0755); err != nil {
		return fmt.Errorf("failed to create dir %s: %w", filepath.Dir(dest), err)
	}

	// Strip CRLF for Linux compatibility
	content := bytes.ReplaceAll(buf.Bytes(), []byte("\r\n"), []byte("\n"))

	if err := os.WriteFile(dest, content, 0644); err != nil {
		return fmt.Errorf("failed to write template to %s: %w", dest, err)
	}

	return nil
}

func (d *Deployer) Clean(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Info(formatted)
	}

	installPaths := resolveKafkaInstallPaths(t)
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}

	log("Starting Kafka cleanup process...")

	// 1. Stop and disable systemd services
	log("Stopping Kafka systemd services...")
	for _, service := range []string{"broker", "controller", "kafka"} {
		d.exec.RunSudo(ctx, "systemctl", "stop", service)
		d.exec.RunSudo(ctx, "systemctl", "disable", service)
	}

	log("Removing systemd unit files...")
	for _, unit := range []string{"broker.service", "controller.service", "kafka.service"} {
		d.exec.RunSudo(ctx, "rm", "-f", filepath.Join("/etc/systemd/system", unit))
	}
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	// 2. Kill remaining processes on ports
	log("Terminating processes on port 9092, 9093, 9095, 7071...")
	d.exec.RunSudo(ctx, "fuser", "-k", "9092/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9093/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9095/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "7071/tcp")
	time.Sleep(2 * time.Second)

	// 3. Remove files
	log("Removing Kafka active symlink: %s", installPaths.ActiveDir)
	d.exec.RunSudo(ctx, "rm", "-f", installPaths.ActiveDir)
	log("Removing Kafka versioned binary directory: %s", installPaths.VersionedDir)
	d.exec.RunSudo(ctx, "rm", "-rf", installPaths.VersionedDir)
	log("Purging Kafka data directory: %s", dataDir)
	d.exec.RunSudo(ctx, "rm", "-rf", dataDir)

	// 4. Validate ports are free
	log("Validating ports are free...")
	out, _, _ := d.exec.RunSudo(ctx, "ss", "-tlnp")
	if strings.Contains(out, ":9092 ") || strings.Contains(out, ":9093 ") || strings.Contains(out, ":9095 ") || strings.Contains(out, ":7071 ") {
		return logs.String(), fmt.Errorf("Ports are still in use after cleanup")
	}

	log("Cleanup completed successfully.")
	return logs.String(), nil
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
