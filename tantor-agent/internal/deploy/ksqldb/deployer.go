package ksqldb

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
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	installDir := t.Parameters["install_dir"]
	if installDir == "" {
		installDir = "/opt/tantor/ksqldb"
	}
	kafkaUser := "kafka"

	log("Starting ksqlDB Deployment...")

	// Directories
	d.exec.RunSudo(ctx, "mkdir", "-p", installDir)
	d.exec.RunSudo(ctx, "mkdir", "-p", d.cfg.Paths.ArtifactsDir)

	// Artifact Download
	destPath := filepath.Join(d.cfg.Paths.ArtifactsDir, fmt.Sprintf("ksqldb_%s.tgz", t.TaskID))
	log("Downloading artifact to %s", destPath)
	
	downloadedChecksum, err := d.client.DownloadArtifact(t.ArtifactURL, destPath)
	if err != nil {
		return logs.String(), err
	}

	expectedChecksum := t.Checksum
	if expectedChecksum == "" {
		expectedChecksum = downloadedChecksum
	}
	if err := checksum.VerifySHA256(destPath, expectedChecksum); err != nil {
		return logs.String(), err
	}

	// Extract
	tmpDir := filepath.Join(d.cfg.Paths.ArtifactsDir, "extract_ksqldb_"+t.TaskID)
	d.exec.RunSudo(ctx, "mkdir", "-p", tmpDir)
	d.exec.RunSudo(ctx, "tar", "-xzf", destPath, "-C", tmpDir, "--strip-components=1")
	d.exec.RunSudo(ctx, "cp", "-r", tmpDir+"/.", installDir+"/")
	d.exec.RunSudo(ctx, "rm", "-rf", tmpDir)

	// Configs
	if err := d.generateConfigs(ctx, t, installDir); err != nil {
		return logs.String(), err
	}

	// Permissions
	d.exec.RunSudo(ctx, "chown", "-R", kafkaUser+":"+kafkaUser, installDir)

	// Systemd
	if err := d.createSystemdService(ctx, kafkaUser, installDir); err != nil {
		return logs.String(), err
	}

	// Start
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "ksqldb-server")

	log("ksqlDB Deployed and Started successfully")
	return logs.String(), nil
}

func (d *Deployer) generateConfigs(ctx context.Context, t *api.Task, installDir string) error {
	bootstrap := t.Parameters["bootstrap_servers"]
	if bootstrap == "" {
		bootstrap = "PLAINTEXT://localhost:9092"
	}
	schemaUrl := t.Parameters["schema_registry_url"]
	if schemaUrl == "" {
		schemaUrl = "http://localhost:8081"
	}

	props := struct {
		BootstrapServers  string
		SchemaRegistryUrl string
	}{
		BootstrapServers:  bootstrap,
		SchemaRegistryUrl: schemaUrl,
	}

	return d.writeTemplateToSudoFile(ctx, KsqlServerPropertiesTemplate, props, filepath.Join(installDir, "etc/ksqldb/ksql-server.properties"))
}

func (d *Deployer) createSystemdService(ctx context.Context, user, installDir string) error {
	out, _, _ := d.exec.Run(ctx, "bash", "-c", "dirname $(dirname $(readlink -f $(which java)))")
	javaHome := strings.TrimSpace(out)
	if javaHome == "" || javaHome == "." {
		javaHome = "/usr"
	}

	props := struct {
		User       string
		Group      string
		JavaHome   string
		InstallDir string
	}{
		User:       user,
		Group:      user,
		JavaHome:   javaHome,
		InstallDir: installDir,
	}

	return d.writeTemplateToSudoFile(ctx, SystemdTemplate, props, "/etc/systemd/system/ksqldb-server.service")
}

func (d *Deployer) writeTemplateToSudoFile(ctx context.Context, tmplStr string, data interface{}, dest string) error {
	tmpl, err := template.New("tmpl").Parse(tmplStr)
	if err != nil {
		return err
	}
	tmpFile, err := os.CreateTemp("", "ksqldb-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmpFile.Name())

	if err := tmpl.Execute(tmpFile, data); err != nil {
		return err
	}
	tmpFile.Close()

	d.exec.RunSudo(ctx, "cp", tmpFile.Name(), dest)
	return nil
}
