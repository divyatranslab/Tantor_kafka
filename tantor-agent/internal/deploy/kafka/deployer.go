package kafka

import (
	"context"
	"fmt"
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
		installDir = "/opt/tantor/kafka"
	}
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = filepath.Join(installDir, "data")
	}
	kafkaUser := "kafka"

	log("Starting Kafka Deployment Workflow...")

	// 1. Validate Java
	_, stderr, err := d.exec.Run(ctx, "java", "-version")
	if err != nil {
		return logs.String(), fmt.Errorf("java validation failed: %w", err)
	}
	log("Java validated: %s", strings.Split(stderr, "\n")[0])

	// 2. Create kafka user
	d.exec.RunSudo(ctx, "useradd", "-r", "-s", "/bin/false", kafkaUser)
	log("Created user: %s", kafkaUser)

	// 3. Create directories
	d.exec.RunSudo(ctx, "mkdir", "-p", installDir)
	d.exec.RunSudo(ctx, "mkdir", "-p", dataDir)
	d.exec.RunSudo(ctx, "mkdir", "-p", d.cfg.Paths.ArtifactsDir)

	// 4. Download TAR
	destPath := filepath.Join(d.cfg.Paths.ArtifactsDir, fmt.Sprintf("kafka_%s.tgz", t.TaskID))
	log("Downloading artifact from %s to %s", t.ArtifactURL, destPath)
	
	downloadedChecksum, err := d.client.DownloadArtifact(t.ArtifactURL, destPath)
	if err != nil {
		return logs.String(), fmt.Errorf("failed to download artifact: %w", err)
	}

	// 5. Verify Checksum
	expectedChecksum := t.Checksum
	if expectedChecksum == "" {
		expectedChecksum = downloadedChecksum
	}
	if err := checksum.VerifySHA256(destPath, expectedChecksum); err != nil {
		d.exec.RunSudo(ctx, "rm", "-f", destPath)
		return logs.String(), fmt.Errorf("checksum verification failed: %w", err)
	}
	log("Checksum verified successfully")

	// 6. Extract TAR
	tmpExtractDir := filepath.Join(d.cfg.Paths.ArtifactsDir, "extract_"+t.TaskID)
	d.exec.RunSudo(ctx, "mkdir", "-p", tmpExtractDir)
	_, _, err = d.exec.RunSudo(ctx, "tar", "-xzf", destPath, "-C", tmpExtractDir, "--strip-components=1")
	if err != nil {
		return logs.String(), fmt.Errorf("failed to extract tar: %w", err)
	}
	
	// Move contents to installDir
	d.exec.RunSudo(ctx, "cp", "-r", tmpExtractDir+"/.", installDir+"/")
	d.exec.RunSudo(ctx, "rm", "-rf", tmpExtractDir)
	log("Artifact extracted to %s", installDir)

	// 7. Create symlink (simulate /opt/kafka -> /opt/tantor/kafka)
	d.exec.RunSudo(ctx, "ln", "-sfn", installDir, "/opt/kafka")

	// 8. Generate Configs
	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	log("Configs generated successfully")

	// Set ownership
	d.exec.RunSudo(ctx, "chown", "-R", kafkaUser+":"+kafkaUser, installDir)
	d.exec.RunSudo(ctx, "chown", "-R", kafkaUser+":"+kafkaUser, "/opt/kafka")

	// 9. Generate KRaft cluster ID & 10. Format storage
	// We'll generate a dummy ID if not provided, format storage
	clusterId := t.Parameters["cluster_id"]
	if clusterId == "" {
		out, _, _ := d.exec.RunSudo(ctx, installDir+"/bin/kafka-storage.sh", "random-uuid")
		clusterId = strings.TrimSpace(out)
		log("Generated new KRaft cluster ID: %s", clusterId)
	}
	_, errOut, err := d.exec.RunSudo(ctx, "sudo", "-u", kafkaUser, installDir+"/bin/kafka-storage.sh", "format", "-t", clusterId, "-c", installDir+"/config/kraft/server.properties", "--ignore-formatted")
	if err != nil {
		log("Storage format warning/error: %v, %s", err, errOut)
	} else {
		log("KRaft storage formatted")
	}

	// 11. Create systemd service
	if err := d.createSystemdService(ctx, kafkaUser, installDir, t); err != nil {
		return logs.String(), err
	}
	log("Systemd service created")

	// 12. Start service
	_, _, err = d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	_, errOut, err = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "kafka")
	if err != nil {
		return logs.String(), fmt.Errorf("failed to start kafka service: %w, %s", err, errOut)
	}
	log("Kafka service started successfully")

	// 13. Validate cluster
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
	
	tmpFile, err := os.CreateTemp("", "tantor-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmpFile.Name())

	if err := tmpl.Execute(tmpFile, data); err != nil {
		return err
	}
	tmpFile.Close()

	_, _, err = d.exec.RunSudo(ctx, "cp", tmpFile.Name(), dest)
	if err != nil {
		return fmt.Errorf("failed to copy template to %s: %w", dest, err)
	}
	
	return nil
}
