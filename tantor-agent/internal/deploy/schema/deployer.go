package schema

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
		installDir = "/opt/tantor/schema-registry"
	}
	serviceUser := defaultValue(t.Parameters["service_user"], "root")
	configDir := defaultValue(t.Parameters["config_dir"], filepath.Join(installDir, "etc/schema-registry"))
	logDir := defaultValue(t.Parameters["log_dir"], "/var/log/tantor/schema-registry")
	workingDir := defaultValue(t.Parameters["working_dir"], "/var/lib/tantor/schema-registry")
	for _, path := range []string{installDir, configDir, logDir, workingDir} {
		if err := validatePath(path); err != nil {
			return logs.String(), err
		}
	}

	log("Starting Schema Registry Deployment...")

	// Directories
	if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", installDir, configDir, logDir, workingDir, d.cfg.Paths.ArtifactsDir); err != nil {
		return logs.String(), fmt.Errorf("create Schema Registry directories: %w: %s", err, errOut)
	}

	// Artifact Download
	destPath := filepath.Join(d.cfg.Paths.ArtifactsDir, fmt.Sprintf("schema_%s.tgz", t.TaskID))
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
	tmpDir := filepath.Join(d.cfg.Paths.ArtifactsDir, "extract_schema_"+t.TaskID)
	if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", tmpDir); err != nil {
		return logs.String(), fmt.Errorf("create extraction directory: %w: %s", err, errOut)
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "tar", "-xzf", destPath, "-C", tmpDir, "--strip-components=1"); err != nil {
		return logs.String(), fmt.Errorf("extract Schema Registry artifact: %w: %s", err, errOut)
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "cp", "-r", tmpDir+"/.", installDir+"/"); err != nil {
		return logs.String(), fmt.Errorf("install Schema Registry artifact: %w: %s", err, errOut)
	}
	_, _, _ = d.exec.RunSudo(ctx, "rm", "-rf", tmpDir)

	// Configs
	if err := d.generateConfigs(ctx, t, installDir); err != nil {
		return logs.String(), err
	}

	// Permissions
	if _, errOut, err := d.exec.RunSudo(ctx, "chown", "-R", serviceUser+":"+serviceUser, installDir, configDir, logDir, workingDir); err != nil {
		return logs.String(), fmt.Errorf("set Schema Registry ownership: %w: %s", err, errOut)
	}

	// Systemd
	if err := d.createSystemdService(ctx, serviceUser, installDir, configDir, logDir, workingDir, defaultValue(t.Parameters["heap_size"], "1G")); err != nil {
		return logs.String(), err
	}

	// Start
	if _, errOut, err := d.exec.RunSudo(ctx, "systemctl", "daemon-reload"); err != nil {
		return logs.String(), fmt.Errorf("reload systemd: %w: %s", err, errOut)
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "schema-registry"); err != nil {
		return logs.String(), fmt.Errorf("start Schema Registry: %w: %s", err, errOut)
	}

	log("Schema Registry Deployed and Started successfully")
	return logs.String(), nil
}

func (d *Deployer) generateConfigs(ctx context.Context, t *api.Task, installDir string) error {
	bootstrap := t.Parameters["bootstrap_servers"]
	if strings.TrimSpace(bootstrap) == "" {
		return fmt.Errorf("bootstrap_servers is required for Schema Registry deployment")
	}

	props := struct {
		BootstrapServers, RestPort, HostName, KafkaStoreTopic, ReplicationFactor, GroupID, CompatibilityLevel string
	}{
		BootstrapServers:   bootstrap,
		RestPort:           defaultValue(t.Parameters["rest_port"], "8081"),
		HostName:           defaultValue(t.Parameters["host_name"], "localhost"),
		KafkaStoreTopic:    defaultValue(t.Parameters["kafkastore_topic"], "_schemas"),
		ReplicationFactor:  defaultValue(t.Parameters["replication_factor"], "1"),
		GroupID:            defaultValue(t.Parameters["group_id"], "schema-registry"),
		CompatibilityLevel: defaultValue(t.Parameters["compatibility_level"], "BACKWARD"),
	}

	configDir := defaultValue(t.Parameters["config_dir"], filepath.Join(installDir, "etc/schema-registry"))
	return d.writeTemplateToSudoFile(ctx, SchemaRegistryPropertiesTemplate, props, filepath.Join(configDir, "schema-registry.properties"))
}

func (d *Deployer) createSystemdService(ctx context.Context, user, installDir, configDir, logDir, workingDir, heapSize string) error {
	out, _, _ := d.exec.Run(ctx, "readlink", "-f", "/usr/bin/java")
	javaHome := strings.TrimSpace(out)
	javaHome = filepath.Dir(filepath.Dir(javaHome))
	if javaHome == "" || javaHome == "." {
		javaHome = "/usr"
	}

	props := struct {
		User, Group, JavaHome, InstallDir, ConfigDir, LogDir, WorkingDir, HeapSize string
	}{
		User:       user,
		Group:      user,
		JavaHome:   javaHome,
		InstallDir: installDir,
		ConfigDir:  configDir,
		LogDir:     logDir,
		WorkingDir: workingDir,
		HeapSize:   heapSize,
	}

	return d.writeTemplateToSudoFile(ctx, SystemdTemplate, props, "/etc/systemd/system/schema-registry.service")
}

func defaultValue(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func validatePath(value string) error {
	clean := filepath.Clean(strings.TrimSpace(value))
	if clean == "." || clean == "" || !filepath.IsAbs(clean) || clean == string(filepath.Separator) {
		return fmt.Errorf("unsafe Schema Registry path %q", value)
	}
	return nil
}

func (d *Deployer) writeTemplateToSudoFile(ctx context.Context, tmplStr string, data interface{}, dest string) error {
	tmpl, err := template.New("tmpl").Parse(tmplStr)
	if err != nil {
		return err
	}
	tmpFile, err := os.CreateTemp("", "schema-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmpFile.Name())

	if err := tmpl.Execute(tmpFile, data); err != nil {
		return err
	}
	tmpFile.Close()

	if _, errOut, err := d.exec.RunSudo(ctx, "cp", tmpFile.Name(), dest); err != nil {
		return fmt.Errorf("write %s: %w: %s", dest, err, errOut)
	}
	return nil
}
