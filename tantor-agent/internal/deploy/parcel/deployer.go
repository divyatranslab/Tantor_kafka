package parcel

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/pkg/api"
	"io.translab/tantor-agent/pkg/checksum"
)

const defaultParcelDir = "/srv/apps/tantor/parcels"

type Deployer struct {
	cfg    *config.Config
	client *client.APIClient
	exec   executor.Executor
}

func NewDeployer(cfg *config.Config, client *client.APIClient, exec executor.Executor) *Deployer {
	return &Deployer{cfg: cfg, client: client, exec: exec}
}

func (d *Deployer) Distribute(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	meta := parcelMeta(t)
	log("Starting parcel distribution for %s %s", meta.ServiceType, meta.Version)

	if t.ArtifactURL == "" {
		return logs.String(), fmt.Errorf("artifact URL is required")
	}
	if err := os.MkdirAll(d.cfg.Paths.ArtifactsDir, 0755); err != nil {
		return logs.String(), fmt.Errorf("failed to prepare artifact cache: %w", err)
	}

	downloadPath := filepath.Join(d.cfg.Paths.ArtifactsDir, fmt.Sprintf("parcel_%s_%s.tgz", meta.ServiceType, meta.Version))
	log("Downloading parcel from %s to %s", t.ArtifactURL, downloadPath)
	downloadedChecksum, err := d.client.DownloadArtifact(t.ArtifactURL, downloadPath)
	if err != nil {
		return logs.String(), fmt.Errorf("failed to download parcel: %w", err)
	}

	expectedChecksum := firstNonEmpty(t.Checksum, t.Parameters["checksum"], downloadedChecksum)
	if expectedChecksum != "" {
		if err := checksum.VerifySHA256(downloadPath, expectedChecksum); err != nil {
			return logs.String(), fmt.Errorf("checksum verification failed: %w", err)
		}
		log("Checksum verified successfully")
	}

	if err := d.prepareBaseDirs(ctx, meta); err != nil {
		return logs.String(), err
	}

	tmpDir := filepath.Join(meta.BaseDir, ".tmp", t.TaskID)
	installDir := meta.InstallDir()
	parentDir := filepath.Dir(installDir)
	cachePath := filepath.Join(meta.BaseDir, ".downloads", filepath.Base(downloadPath))

	script := fmt.Sprintf(
		"set -e; rm -rf %s %s; mkdir -p %s %s %s; tar -xzf %s -C %s --strip-components=1; cp %s %s; chmod -R a+rX %s; find %s/bin -type f -name '*.sh' -exec chmod a+x {} + || true",
		shellQuote(tmpDir),
		shellQuote(installDir),
		shellQuote(tmpDir),
		shellQuote(parentDir),
		shellQuote(filepath.Dir(cachePath)),
		shellQuote(downloadPath),
		shellQuote(tmpDir),
		shellQuote(downloadPath),
		shellQuote(cachePath),
		shellQuote(tmpDir),
		shellQuote(tmpDir),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return logs.String(), fmt.Errorf("failed to extract parcel: %w, out: %s, err: %s", err, out, errOut)
	}

	if out, errOut, err := d.exec.RunSudo(ctx, "mv", tmpDir, installDir); err != nil {
		return logs.String(), fmt.Errorf("failed to stage parcel: %w, out: %s, err: %s", err, out, errOut)
	}

	log("Parcel distributed to %s", installDir)
	log("Cached parcel archive at %s", cachePath)
	return logs.String(), nil
}

func (d *Deployer) Activate(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	meta := parcelMeta(t)
	if err := d.prepareBaseDirs(ctx, meta); err != nil {
		return logs.String(), err
	}

	target := meta.InstallDir()
	link := meta.ActiveLink()
	metadataFile := filepath.Join(meta.BaseDir, "active", meta.ServiceType+".active")
	script := fmt.Sprintf(
		"set -e; test -d %s; mkdir -p %s; ln -sfn %s %s; printf 'artifact_id=%s\\nservice_type=%s\\nversion=%s\\n' > %s",
		shellQuote(target),
		shellQuote(filepath.Dir(link)),
		shellQuote(target),
		shellQuote(link),
		shellEscape(meta.ArtifactID),
		shellEscape(meta.ServiceType),
		shellEscape(meta.Version),
		shellQuote(metadataFile),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return logs.String(), fmt.Errorf("failed to activate parcel: %w, out: %s, err: %s", err, out, errOut)
	}

	log("Activated %s %s", meta.ServiceType, meta.Version)
	log("Active link: %s -> %s", link, target)
	return logs.String(), nil
}

func (d *Deployer) Deactivate(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	meta := parcelMeta(t)
	link := meta.ActiveLink()
	metadataFile := filepath.Join(meta.BaseDir, "active", meta.ServiceType+".active")
	script := fmt.Sprintf("set -e; rm -f %s %s", shellQuote(link), shellQuote(metadataFile))
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return logs.String(), fmt.Errorf("failed to deactivate parcel: %w, out: %s, err: %s", err, out, errOut)
	}

	log("Deactivated %s %s", meta.ServiceType, meta.Version)
	return logs.String(), nil
}

func (d *Deployer) Remove(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	meta := parcelMeta(t)
	target := meta.InstallDir()
	link := meta.ActiveLink()
	archive := filepath.Join(meta.BaseDir, ".downloads", fmt.Sprintf("parcel_%s_%s.tgz", meta.ServiceType, meta.Version))
	script := fmt.Sprintf(
		"set -e; if [ -L %s ] && [ \"$(readlink -f %s)\" = \"$(readlink -f %s)\" ]; then echo 'parcel is active; deactivate before remove' >&2; exit 42; fi; rm -rf %s %s",
		shellQuote(link),
		shellQuote(link),
		shellQuote(target),
		shellQuote(target),
		shellQuote(archive),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return logs.String(), fmt.Errorf("failed to remove parcel: %w, out: %s, err: %s", err, out, errOut)
	}

	log("Removed %s %s from %s", meta.ServiceType, meta.Version, target)
	return logs.String(), nil
}

func (d *Deployer) prepareBaseDirs(ctx context.Context, meta parcelMetadata) error {
	script := fmt.Sprintf(
		"set -e; mkdir -p %s %s %s",
		shellQuote(meta.BaseDir),
		shellQuote(filepath.Join(meta.BaseDir, ".downloads")),
		shellQuote(filepath.Join(meta.BaseDir, "active")),
	)
	if out, errOut, err := d.exec.RunSudo(ctx, "bash", "-c", script); err != nil {
		return fmt.Errorf("failed to prepare parcel directories: %w, out: %s, err: %s", err, out, errOut)
	}
	return nil
}

type parcelMetadata struct {
	ArtifactID  string
	ServiceType string
	Version     string
	BaseDir     string
}

func (m parcelMetadata) InstallDir() string {
	return filepath.Join(m.BaseDir, m.ServiceType, m.Version)
}

func (m parcelMetadata) ActiveLink() string {
	return filepath.Join(m.BaseDir, "active", m.ServiceType)
}

func parcelMeta(t *api.Task) parcelMetadata {
	return parcelMetadata{
		ArtifactID:  safeSegment(firstNonEmpty(t.Parameters["artifact_id"], t.TaskID)),
		ServiceType: safeSegment(strings.ToLower(firstNonEmpty(t.Parameters["service_type"], "kafka"))),
		Version:     safeSegment(firstNonEmpty(t.Parameters["version"], "unknown")),
		BaseDir:     firstNonEmpty(t.Parameters["parcel_dir"], defaultParcelDir),
	}
}

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func safeSegment(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "unknown"
	}
	var b strings.Builder
	for _, r := range value {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '.' || r == '_' || r == '-' {
			b.WriteRune(r)
		} else {
			b.WriteRune('_')
		}
	}
	return b.String()
}

func shellQuote(value string) string {
	return "'" + strings.ReplaceAll(value, "'", "'\"'\"'") + "'"
}

func shellEscape(value string) string {
	return strings.ReplaceAll(value, "'", "'\"'\"'")
}
