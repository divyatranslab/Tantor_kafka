package kafka

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"text/template"
	"time"
	"net"

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

	log("Starting cross-platform Kafka Deployment Workflow...")

	// 1. Create directories
	os.MkdirAll(installDir, 0755)
	os.MkdirAll(dataDir, 0755)
	os.MkdirAll(d.cfg.Paths.ArtifactsDir, 0755)

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
			os.Remove(dest) // Force recreation to avoid preserving old permissions
			os.WriteFile(dest, data, mode)
		}
		return nil
	})
	os.RemoveAll(tmpExtractDir)
	log("Artifact extracted to %s", installDir)

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
	logDirs := t.Parameters["log_dirs"]
	if logDirs == "" {
		logDirs = filepath.Join(dataDir, "kafka-logs")
	}

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

	// 8. Systemd Service
	if err := d.createSystemdService(ctx, "root", installDir, t); err != nil {
		return logs.String(), err
	}
	log("Systemd service created")

	// 9. Start Service
	_, _, err = d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	if err == nil {
		_, _, err = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "kafka")
	}
	if err != nil {
		return logs.String(), fmt.Errorf("failed to start service: %w", err)
	}
	log("Kafka service started successfully")

	// 10. Post-Deployment Validation
	if err := d.validateDeployment(ctx, t, installDir, &logs); err != nil {
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
	nodeId := t.Parameters["node_id"]
	if nodeId == "" {
		nodeId = "1"
	}
	hostname := getLocalIP()
	quorumVoters := t.Parameters["quorum_voters"]
	if quorumVoters == "" {
		quorumVoters = fmt.Sprintf("%s@%s:9093", nodeId, hostname)
	}

	role := t.Parameters["role"]
	if role == "broker_controller" || role == "" {
		role = "broker,controller"
	}

	listenerPort := t.Parameters["listener_port"]
	if listenerPort == "" {
		listenerPort = "9092"
	}

	controllerPort := t.Parameters["controller_port"]
	if controllerPort == "" {
		controllerPort = "9093"
	}

	logDirs := t.Parameters["log_dirs"]
	if logDirs == "" {
		logDirs = filepath.Join(dataDir, "kafka-logs")
	}

	numPartitions := t.Parameters["num_partitions"]
	if numPartitions == "" {
		numPartitions = "1"
	}

	repFactor := t.Parameters["replication_factor"]
	if repFactor == "" {
		repFactor = "1"
	}

	props := struct {
		NodeId         string
		QuorumVoters   string
		Hostname       string
		LogDirs        string
		Role           string
		ListenerPort   string
		ControllerPort string
		NumPartitions  string
		RepFactor      string
	}{
		NodeId:         nodeId,
		QuorumVoters:   quorumVoters,
		Hostname:       hostname,
		LogDirs:        logDirs,
		Role:           role,
		ListenerPort:   listenerPort,
		ControllerPort: controllerPort,
		NumPartitions:  numPartitions,
		RepFactor:      repFactor,
	}

	return d.writeTemplateToSudoFile(ctx, ServerPropertiesTemplate, props, filepath.Join(installDir, "config/kraft/server.properties"))
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

	props := struct {
		User       string
		Group      string
		JavaHome   string
		InstallDir string
		HeapSize   string
		JmxPort    string
	}{
		User:       user,
		Group:      user,
		JavaHome:   javaHome,
		InstallDir: installDir,
		HeapSize:   heapSize,
		JmxPort:    jmxPort,
	}

	return d.writeTemplateToSudoFile(ctx, SystemdTemplate, props, "/etc/systemd/system/kafka.service")
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

	installDir := t.Parameters["kafka_install_dir"]
	dataDir := t.Parameters["kafka_data_dir"]
	logDirs := t.Parameters["log_dirs"]
	listenerPort := t.Parameters["listener_port"]
	controllerPort := t.Parameters["controller_port"]

	if installDir == "" {
		installDir = "/data/apps/kafka/install"
	}
	if dataDir == "" {
		dataDir = "/data/apps/kafka/data"
	}
	if logDirs == "" {
		logDirs = "/data/apps/kafka/logs"
	}
	if listenerPort == "" {
		listenerPort = "9092"
	}
	if controllerPort == "" {
		controllerPort = "9093"
	}

	log("Starting Kafka cleanup process...")

	// 1. Stop and disable systemd service
	log("Stopping kafka.service...")
	d.exec.RunSudo(ctx, "systemctl", "stop", "kafka")
	d.exec.RunSudo(ctx, "systemctl", "disable", "kafka")

	log("Removing systemd unit file...")
	d.exec.RunSudo(ctx, "rm", "-f", "/etc/systemd/system/kafka.service")
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")

	// 2. Kill remaining processes on ports
	log("Terminating processes on port %s, %s, 7071...", listenerPort, controllerPort)
	d.exec.RunSudo(ctx, "fuser", "-k", listenerPort+"/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", controllerPort+"/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "7071/tcp")
	time.Sleep(2 * time.Second)

	// 3. Remove files
	log("Purging directories: %s, %s, %s", installDir, dataDir, logDirs)
	d.exec.RunSudo(ctx, "rm", "-rf", installDir)
	d.exec.RunSudo(ctx, "rm", "-rf", dataDir)
	d.exec.RunSudo(ctx, "rm", "-rf", logDirs)

	// 4. Validate ports are free
	log("Validating ports are free...")
	out, _, _ := d.exec.RunSudo(ctx, "ss", "-tlnp")
	if strings.Contains(out, ":"+listenerPort+" ") || strings.Contains(out, ":"+controllerPort+" ") || strings.Contains(out, ":7071 ") {
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
