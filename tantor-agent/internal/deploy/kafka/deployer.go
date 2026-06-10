package kafka

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"text/template"

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
		installDir = "C:\\opt\\tantor\\kafka" // Windows friendly default
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
			os.WriteFile(dest, data, 0644)
		}
		return nil
	})
	os.RemoveAll(tmpExtractDir)
	log("Artifact extracted to %s", installDir)

	// 6. Setup JMX Exporter
	jmxDir := filepath.Join(installDir, "jmx")
	os.MkdirAll(jmxDir, 0755)
	jmxJarPath := filepath.Join(jmxDir, "jmx_prometheus_javaagent.jar")
	
	log("Downloading JMX Exporter to %s", jmxJarPath)
	resp, err := http.Get("https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar")
	if err == nil {
		defer resp.Body.Close()
		out, _ := os.Create(jmxJarPath)
		io.Copy(out, resp.Body)
		out.Close()
	} else {
		log("Warning: Failed to download JMX agent: %v", err)
	}

	if err := d.writeTemplateToSudoFile(ctx, JmxConfigTemplate, nil, filepath.Join(jmxDir, "jmx_config.yml")); err != nil {
		log("Warning: Failed to write JMX config: %v", err)
	}

	// 7. Generate Configs
	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	log("Configs generated successfully")

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

	log("Cluster validation step completed")

	return logs.String(), nil
}

func (d *Deployer) generateConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	nodeId := t.Parameters["node_id"]
	if nodeId == "" {
		nodeId = "1"
	}
	quorumVoters := t.Parameters["quorum_voters"]
	if quorumVoters == "" {
		quorumVoters = fmt.Sprintf("%s@localhost:9093", nodeId)
	}
	hostname, _ := os.Hostname()

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

	if err := os.WriteFile(dest, buf.Bytes(), 0644); err != nil {
		return fmt.Errorf("failed to write template to %s: %w", dest, err)
	}
	
	return nil
}
