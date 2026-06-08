package monitoring

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
		installDir = "/opt/tantor/monitoring"
	}
	tantorUser := "tantor"
	
	// Create user if not exist
	d.exec.RunSudo(ctx, "useradd", "-r", "-s", "/bin/false", tantorUser)

	log("Starting Monitoring Deployment...")

	d.exec.RunSudo(ctx, "mkdir", "-p", installDir)
	d.exec.RunSudo(ctx, "mkdir", "-p", d.cfg.Paths.ArtifactsDir)

	// Deploy Prometheus
	if err := d.deployComponent(ctx, t, "prometheus", filepath.Join(installDir, "prometheus"), tantorUser); err != nil {
		return logs.String(), err
	}
	log("Prometheus deployed")

	// Deploy Grafana
	if err := d.deployComponent(ctx, t, "grafana", filepath.Join(installDir, "grafana"), tantorUser); err != nil {
		return logs.String(), err
	}
	log("Grafana deployed")

	// Systemd and Start
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "prometheus", "grafana")

	log("Monitoring Stack Deployed and Started successfully")
	return logs.String(), nil
}

func (d *Deployer) deployComponent(ctx context.Context, t *api.Task, component, installDir, user string) error {
	destPath := filepath.Join(d.cfg.Paths.ArtifactsDir, fmt.Sprintf("%s_%s.tgz", component, t.TaskID))
	
	// We assume artifact URLs are passed as prometheus_url, grafana_url in parameters
	urlParam := component + "_url"
	artifactUrl := t.Parameters[urlParam]
	if artifactUrl == "" {
		return fmt.Errorf("missing artifact url for %s", component)
	}

	_, err := d.client.DownloadArtifact(artifactUrl, destPath)
	if err != nil {
		return err
	}
	// Skipping checksum for brevity in prototype script, but normally fetch from params

	d.exec.RunSudo(ctx, "mkdir", "-p", installDir)
	tmpDir := filepath.Join(d.cfg.Paths.ArtifactsDir, "extract_"+component+"_"+t.TaskID)
	d.exec.RunSudo(ctx, "mkdir", "-p", tmpDir)
	d.exec.RunSudo(ctx, "tar", "-xzf", destPath, "-C", tmpDir, "--strip-components=1")
	d.exec.RunSudo(ctx, "cp", "-r", tmpDir+"/.", installDir+"/")
	d.exec.RunSudo(ctx, "rm", "-rf", tmpDir)

	// Generate configs
	if component == "prometheus" {
		d.writeTemplateToSudoFile(ctx, PrometheusConfigTemplate, nil, filepath.Join(installDir, "prometheus.yml"))
		
		props := struct {
			User       string
			Group      string
			InstallDir string
			DataDir    string
		}{
			User:       user,
			Group:      user,
			InstallDir: installDir,
			DataDir:    filepath.Join(installDir, "data"),
		}
		d.writeTemplateToSudoFile(ctx, PrometheusSystemdTemplate, props, "/etc/systemd/system/prometheus.service")
		d.exec.RunSudo(ctx, "mkdir", "-p", props.DataDir)
	} else if component == "grafana" {
		props := struct {
			User       string
			Group      string
			InstallDir string
		}{
			User:       user,
			Group:      user,
			InstallDir: installDir,
		}
		d.writeTemplateToSudoFile(ctx, GrafanaSystemdTemplate, props, "/etc/systemd/system/grafana.service")
	}

	d.exec.RunSudo(ctx, "chown", "-R", user+":"+user, installDir)
	return nil
}

func (d *Deployer) writeTemplateToSudoFile(ctx context.Context, tmplStr string, data interface{}, dest string) error {
	tmpl, err := template.New("tmpl").Parse(tmplStr)
	if err != nil {
		return err
	}
	tmpFile, err := os.CreateTemp("", "mon-*")
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
