package kafka

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/url"
	"os"
	stdpath "path"
	"path/filepath"
	"strconv"
	"strings"
	"text/template"
	"time"

	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/internal/taskvalidate"
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

func firstNonEmpty(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func NewDeployer(cfg *config.Config, client *client.APIClient, exec executor.Executor) *Deployer {
	return &Deployer{
		cfg:    cfg,
		client: client,
		exec:   exec,
	}
}

func (d *Deployer) Deploy(ctx context.Context, t *api.Task, reporter func(step string, log string)) (string, error) {
	var logs strings.Builder
	if err := validateCommandTaskInputs(t); err != nil {
		return logs.String(), err
	}
	var stepLogs strings.Builder
	currentStep := ""

	reportProgress := func() {
		if reporter != nil && currentStep != "" && stepLogs.Len() > 0 {
			reporter(currentStep, stepLogs.String())
			stepLogs.Reset()
		}
	}
	defer reportProgress()

	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		stepLogs.WriteString(formatted + "\n")
	}

	setStep := func(name string) {
		reportProgress()
		currentStep = name
		log("==> Starting step: %s", name)
		reportProgress()
	}

	var DEPLOYMENT_STEPS = []string{
		"Validate agent",
		"Validate host prerequisites",
		"Validate package",
		"Download package to agent",
		"Verify checksum",
		"Extract Kafka",
		"Backup old config if exists",
		"Generate config",
		"Format KRaft storage / setup Zookeeper",
		"Create systemd service",
		"Start service",
		"Validate port",
		"Validate Kafka AdminClient connection",
		"Validate cluster health",
		"Mark DB state RUNNING",
	}

	resumeStep := strings.TrimSpace(t.Parameters["resume_step"])
	resumeStepIdx := -1
	for i, s := range DEPLOYMENT_STEPS {
		if s == resumeStep {
			resumeStepIdx = i
			break
		}
	}

	shouldSkip := func(stepName string) bool {
		if resumeStepIdx == -1 {
			return false
		}
		for i, s := range DEPLOYMENT_STEPS {
			if s == stepName {
				return i < resumeStepIdx
			}
		}
		return false
	}

	setStep("Validate agent")
	log("Deployment target:")
	log("  Host ID: %s", firstNonEmpty(t.Parameters["host_id"], d.cfg.Agent.HostID))
	log("  Hostname: %s", firstNonEmpty(t.Parameters["host_hostname"], "unknown"))
	log("  Host IP: %s", firstNonEmpty(t.Parameters["host_ip"], "unknown"))
	log("  Kafka node ID: %s", firstNonEmpty(t.Parameters["node_id"], "unknown"))
	log("  Service role: %s", firstNonEmpty(t.Parameters["service_role"], t.Parameters["role"], "unknown"))
	log("Agent self-check passed")

	setStep("Validate host prerequisites")
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
	if paths.MetadataLogDir != "" && deploymentModeForTask(t) == "kraft" {
		log("KRaft metadata directory: %s", paths.MetadataLogDir)
	}
	if paths.AppLogDir != "" {
		log("Kafka application log directory: %s", paths.AppLogDir)
	}

	// 1. Create directories
	d.exec.RunSudo(ctx, "mkdir", "-p", installPaths.BaseDir, installDir, dataDir)
	artifactWorkDir := kafkaArtifactWorkDir(t, d.cfg.Paths.ArtifactsDir)
	if err := os.MkdirAll(artifactWorkDir, 0o750); err != nil {
		return logs.String(), fmt.Errorf("prepare agent artifact work directory %s: %w", artifactWorkDir, err)
	}
	for _, dir := range []string{paths.LogDirs, paths.MetadataLogDir, paths.AppLogDir} {
		if dir != "" {
			d.exec.RunSudo(ctx, "mkdir", "-p", strings.TrimSpace(dir))
		}
	}

	setStep("Validate package")
	artifactURL := firstNonEmpty(
		t.ArtifactURL,
		t.Parameters["artifact_url"],
		t.Parameters["artifactUrl"],
		t.Parameters["download_url"],
		t.Parameters["downloadUrl"],
		t.Parameters["artifact_download_url"],
		t.Parameters["artifactDownloadUrl"],
		t.Parameters["file_url"],
		t.Parameters["fileUrl"],
	)
	artifactID := firstNonEmpty(
		t.ArtifactID,
		t.Parameters["artifact_id"],
		t.Parameters["artifactId"],
		t.Parameters["artifact_uuid"],
		t.Parameters["artifactUuid"],
	)
	if artifactURL == "" && artifactID == "" {
		return logs.String(), fmt.Errorf("Kafka artifact reference is missing: deployment task must contain artifactId/artifact_id from the UI-selected artifact")
	}
	if artifactID != "" {
		log("Kafka artifact ID received from deployment task: %s", artifactID)
		if artifactURL != "" {
			log("Artifact ID is present; using canonical management-server GET flow and ignoring direct artifact URL for the primary download")
		}
	} else {
		log("Legacy Kafka artifact URL/reference received: %s", artifactURL)
	}

	setStep("Download package to agent")
	destPath := filepath.Join(artifactWorkDir, fmt.Sprintf("kafka_%s.tgz", t.TaskID))
	var downloadedChecksum string
	var err error

	if !shouldSkip("Download package to agent") {
		if artifactID != "" {
			log("GET Kafka artifact %s from configured management server %s", artifactID, d.cfg.Agent.ServerURL)
			downloadedChecksum, err = d.client.DownloadArtifactByID(artifactID, destPath)
		} else {
			log("Legacy compatibility mode: resolving Kafka artifact URL through configured management server %s", d.cfg.Agent.ServerURL)
			downloadedChecksum, err = d.client.DownloadArtifactReference(artifactURL, "", destPath)
		}
		if err != nil {
			return logs.String(), fmt.Errorf("failed to download artifact from management server: %w", err)
		}
		log("Kafka artifact downloaded successfully from management server to %s", destPath)
	} else {
		log("Skipping step (resume mode)")
	}

	setStep("Verify checksum")
	if !shouldSkip("Verify checksum") {
		expectedChecksum := firstNonEmpty(
			t.Checksum,
			t.Parameters["checksum"],
			t.Parameters["sha256"],
			t.Parameters["sha256sum"],
			t.Parameters["checksum_sha256"],
			t.Parameters["checksumSha256"],
		)
		if expectedChecksum == "" {
			expectedChecksum = downloadedChecksum
		}
		if err := checksum.VerifySHA256(destPath, expectedChecksum); err != nil {
			os.Remove(destPath)
			return logs.String(), fmt.Errorf("checksum verification failed: %w", err)
		}
		log("Checksum verified successfully")
	} else {
		log("Skipping step (resume mode)")
	}

	setStep("Extract Kafka")
	if !shouldSkip("Extract Kafka") {
		installedLauncher := filepath.Join(installDir, "bin", "kafka-server-start.sh")
		if _, _, installedErr := d.exec.RunSudo(ctx, "test", "-f", installedLauncher); installedErr == nil {
			log("Kafka %s is already extracted at %s; preserving existing role configurations", t.Parameters["version"], installDir)
		} else {
			_, _, err = d.exec.RunSudo(ctx, "tar", "-xzf", destPath, "-C", installDir, "--strip-components=1")
			if err != nil {
				return logs.String(), fmt.Errorf("failed to extract tar: %w", err)
			}
			log("Artifact extracted to %s", installDir)
		}

		// Artifact archives are not guaranteed to preserve executable mode bits.
		// Normalize the extracted Kafka tree on every deployment/resume so shell
		// launchers such as kafka-storage.sh are always readable and executable.
		if err := d.normalizeKafkaTreePermissions(ctx, installDir); err != nil {
			return logs.String(), err
		}

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
	} else {
		log("Skipping step (resume mode)")
	}

	// 6. Setup JMX Exporter
	jmxDir := filepath.Join(installDir, "jmx")
	if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", jmxDir); err != nil {
		return logs.String(), fmt.Errorf("failed to create JMX exporter directory %s: %w: %s", jmxDir, err, strings.TrimSpace(errOut))
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "chmod", "755", installDir, jmxDir); err != nil {
		return logs.String(), fmt.Errorf("failed to make JMX exporter directory readable %s: %w: %s", jmxDir, err, strings.TrimSpace(errOut))
	}
	jmxJarPath := filepath.Join(jmxDir, "jmx_prometheus_javaagent.jar")

	log("Downloading JMX Exporter to %s", jmxJarPath)

	jmxURL := firstNonEmpty(
		t.Parameters["jmx_artifact_url"],
		t.Parameters["jmxArtifactUrl"],
		t.Parameters["jmx_download_url"],
		t.Parameters["jmxDownloadUrl"],
	)
	jmxArtifactID := firstNonEmpty(
		t.Parameters["jmx_artifact_id"],
		t.Parameters["jmxArtifactId"],
	)
	jmxInstalled := false
	if jmxURL != "" || jmxArtifactID != "" {
		log("Resolving JMX exporter artifact through the configured management server")
		tmpJmx := filepath.Join(artifactWorkDir, "jmx_tmp.jar")
		_, err = d.client.DownloadArtifactReference(jmxURL, jmxArtifactID, tmpJmx)
		if err != nil {
			log("Warning: Failed to download JMX exporter from the management server: %v", err)
			os.Remove(tmpJmx)
		} else if !isUsableJar(tmpJmx) {
			log("Warning: Downloaded JMX artifact is not a valid jar; keeping existing jar if present")
			os.Remove(tmpJmx)
		} else {
			if _, errOut, err := d.exec.RunSudo(ctx, "mv", tmpJmx, jmxJarPath); err != nil {
				log("Warning: Failed to install JMX exporter jar at %s: %v %s", jmxJarPath, err, strings.TrimSpace(errOut))
				os.Remove(tmpJmx)
			} else if _, errOut, err := d.exec.RunSudo(ctx, "chmod", "755", jmxDir); err != nil {
				log("Warning: Failed to make JMX exporter directory readable at %s: %v %s", jmxDir, err, strings.TrimSpace(errOut))
			} else if _, errOut, err := d.exec.RunSudo(ctx, "chmod", "644", jmxJarPath); err != nil {
				log("Warning: Failed to make JMX exporter jar readable at %s: %v %s", jmxJarPath, err, strings.TrimSpace(errOut))
			} else if !isUsableJar(jmxJarPath) {
				log("Warning: Installed JMX artifact is not readable or not a valid jar at %s", jmxJarPath)
			} else {
				log("JMX exporter jar installed at %s", jmxJarPath)
				jmxInstalled = true
			}
		}
	}

	if !jmxInstalled && isUsableJar(jmxJarPath) {
		log("Using existing valid JMX exporter jar at %s", jmxJarPath)
		jmxInstalled = true
	}

	jmxRequired := strings.EqualFold(strings.TrimSpace(firstNonEmpty(t.Parameters["jmx_required"], t.Parameters["jmxRequired"])), "true")
	if !jmxInstalled {
		if jmxRequired {
			return logs.String(), fmt.Errorf("JMX exporter jar is unavailable and jmx_required=true. Upload/select a JMX_EXPORTER artifact in Tantor (jmx_artifact_id or jmx_artifact_url) and retry deployment")
		}
		log("JMX exporter artifact is not available; continuing Kafka deployment with JMX monitoring disabled")
	} else {
		if err := d.writeTemplateToSudoFile(ctx, JmxConfigTemplate, nil, filepath.Join(jmxDir, "jmx_config.yml")); err != nil {
			return logs.String(), fmt.Errorf("failed to write JMX exporter config: %w", err)
		}
	}

	// 6.5 Setup Kafka Exporter
	kafkaExporterPath := filepath.Join(installDir, "bin", "kafka_exporter")
	kafkaExporterURL := firstNonEmpty(
		t.Parameters["kafka_exporter_artifact_url"],
		t.Parameters["kafkaExporterArtifactUrl"],
		t.Parameters["kafka_exporter_download_url"],
	)
	kafkaExporterArtifactID := firstNonEmpty(
		t.Parameters["kafka_exporter_artifact_id"],
		t.Parameters["kafkaExporterArtifactId"],
	)

	if kafkaExporterURL != "" || kafkaExporterArtifactID != "" {
		log("Resolving kafka_exporter artifact through the configured management server")
		tmpExporter := filepath.Join(artifactWorkDir, "kafka_exporter_tmp.tar.gz")
		_, err = d.client.DownloadArtifactReference(kafkaExporterURL, kafkaExporterArtifactID, tmpExporter)
		if err != nil {
			log("Warning: Failed to download kafka_exporter from the management server: %v", err)
			os.Remove(tmpExporter)
		} else {
			tmpExtracted := filepath.Join(artifactWorkDir, "kafka_exporter_extracted")
			d.exec.RunSudo(ctx, "mkdir", "-p", tmpExtracted)

			if _, errOut, err := d.exec.RunSudo(ctx, "tar", "-xzf", tmpExporter, "-C", tmpExtracted, "--strip-components=1"); err != nil {
				log("Warning: Failed to extract kafka_exporter tar.gz: %v %s", err, strings.TrimSpace(errOut))
			} else {
				extractedBinary := filepath.Join(tmpExtracted, "kafka_exporter")
				if _, errOut, err := d.exec.RunSudo(ctx, "mv", extractedBinary, kafkaExporterPath); err != nil {
					log("Warning: Failed to install kafka_exporter binary at %s: %v %s", kafkaExporterPath, err, strings.TrimSpace(errOut))
				} else if _, errOut, err := d.exec.RunSudo(ctx, "chmod", "755", kafkaExporterPath); err != nil {
					log("Warning: Failed to make kafka_exporter executable at %s: %v %s", kafkaExporterPath, err, strings.TrimSpace(errOut))
				} else {
					log("kafka_exporter binary extracted and installed at %s", kafkaExporterPath)
				}
			}
			d.exec.RunSudo(ctx, "rm", "-rf", tmpExporter, tmpExtracted)
		}
	}

	setStep("Backup old config if exists")
	configPath := configPathForTask(activeInstallDir, t)
	if _, err := os.Stat(configPath); err == nil {
		backupPath := configPath + ".bak." + time.Now().Format("20060102150405")
		log("Backing up existing config: %s -> %s", configPath, backupPath)
		d.exec.RunSudo(ctx, "cp", configPath, backupPath)
	} else {
		log("No existing config to backup")
	}

	setStep("Generate config")
	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		return logs.String(), err
	}
	log("Configs generated successfully")

	setStep("Format KRaft storage / setup Zookeeper")
	if !shouldSkip("Format KRaft storage / setup Zookeeper") {
		// ZooKeeper clusters keep metadata in ZooKeeper and must never run kafka-storage.sh.
		if deploymentModeForTask(t) == "kraft" {
			metaPropsDirs := []string{
				paths.MetadataLogDir,
				paths.LogDirs,
				"/data/kafka/controller-data/metadata",
				"/data/kafka/controller-data/logs",
				"/data/kafka/broker-metadata",
				"/data/kafka/broker-data",
				"/data/kafka/data",
				"/tmp/kafka-logs",
			}
			clusterUUID := strings.TrimSpace(t.Parameters["cluster_uuid"])
			nodeID := strings.TrimSpace(t.Parameters["node_id"])
			if clusterUUID == "" || nodeID == "" {
				return logs.String(), fmt.Errorf("cluster_uuid and node_id are required before formatting KRaft storage")
			}
			metaPropertiesPath := filepath.Join(paths.MetaPropertiesDir, "meta.properties")
			metaExists, err := d.privilegedFileExists(ctx, metaPropertiesPath)
			if err != nil {
				return logs.String(), fmt.Errorf("failed to inspect KRaft metadata %s: %w", metaPropertiesPath, err)
			}
			if !metaExists {
				log("Fresh deployment detected — formatting KRaft storage...")
				storageScript := filepath.Join(activeInstallDir, "bin", "kafka-storage.sh")
				configPath := configPathForTask(activeInstallDir, t)
				formatArgs := []string{"format", "--cluster-id=" + clusterUUID, "-c", configPath}
				if strings.EqualFold(strings.TrimSpace(t.Parameters["kraft_quorum_mode"]), "dynamic") {
					_, _, isController := normalizeKRaftRole(t.Parameters["service_role"])
					if isController {
						initialControllers := strings.TrimSpace(t.Parameters["initial_controllers"])
						if initialControllers == "" {
							return logs.String(), fmt.Errorf("initial_controllers is required to format a dynamic KRaft controller")
						}
						formatArgs = append(formatArgs, "--initial-controllers", initialControllers)
					} else {
						formatArgs = append(formatArgs, "--no-initial-controllers")
					}
				}

				log("Formatting storage with shared cluster ID %s and node ID %s", clusterUUID, nodeID)
				// KRaft metadata directories are created through privileged operations and may
				// intentionally be inaccessible to the non-root agent account. Formatting
				// therefore runs through the configured privileged executor rather than
				// depending on ambient directory ownership.
				formatOut, formatErr, err := d.exec.RunSudo(ctx, storageScript, formatArgs...)
				if err != nil {
					technicalOutput := strings.TrimSpace(strings.TrimSpace(formatOut) + "\n" + strings.TrimSpace(formatErr))
					if technicalOutput == "" {
						technicalOutput = "Kafka storage command returned no diagnostic output"
					}
					log("KRaft storage format failed on host %s (%s), Kafka node %s: %s",
						firstNonEmpty(t.Parameters["host_hostname"], d.cfg.Agent.HostID),
						firstNonEmpty(t.Parameters["host_ip"], "IP unknown"), nodeID, technicalOutput)
					return logs.String(), fmt.Errorf("failed to format KRaft storage on host %s (%s), Kafka node %s: %w: %s",
						firstNonEmpty(t.Parameters["host_hostname"], d.cfg.Agent.HostID),
						firstNonEmpty(t.Parameters["host_ip"], "IP unknown"), nodeID, err, technicalOutput)
				}
				if err := validateMetaProperties(ctx, d, metaPropsDirs, clusterUUID, "", false); err != nil {
					return logs.String(), fmt.Errorf("formatted storage cluster identity validation failed: %w", err)
				}
				if err := validateMetaProperties(ctx, d, []string{paths.MetaPropertiesDir}, clusterUUID, nodeID, true); err != nil {
					return logs.String(), fmt.Errorf("formatted storage identity validation failed: %w", err)
				}
				log("KRaft storage formatted successfully")
			} else {
				if err := validateMetaProperties(ctx, d, metaPropsDirs, clusterUUID, "", false); err != nil {
					return logs.String(), fmt.Errorf("refusing to reuse KRaft storage: %w", err)
				}
				if err := validateMetaProperties(ctx, d, []string{paths.MetaPropertiesDir}, clusterUUID, nodeID, true); err != nil {
					return logs.String(), fmt.Errorf("refusing to reuse KRaft storage: %w", err)
				}
				log("Existing KRaft metadata matches cluster ID %s and node ID %s; skipping format", clusterUUID, nodeID)
			}
		} else {
			log("Zookeeper mode detected — bypassing KRaft format")
		}
	} else {
		log("Skipping step (resume mode)")
	}

	setStep("Create systemd service")
	serviceName := serviceNameForTask(t)
	if err := d.createSystemdService(ctx, "root", activeInstallDir, t); err != nil {
		return logs.String(), err
	}
	log("Systemd service created")

	setStep("Configure firewall")
	if !shouldSkip("Configure firewall") {
		exporterPort := t.Parameters["kafka_exporter_port"]
		if exporterPort == "" {
			exporterPort = "9308"
		}
		serverURL := d.cfg.Agent.ServerURL
		serverIP := ""
		if parsedURL, err := url.Parse(serverURL); err == nil && parsedURL != nil {
			serverIP = parsedURL.Hostname()
		}
		if serverIP != "" {
			log("Configuring firewall to restrict exporter port %s to server %s", exporterPort, serverIP)
			if _, _, checkErr := d.exec.RunSudo(ctx, "systemctl", "is-active", "firewalld"); checkErr == nil {
				d.exec.RunSudo(ctx, "firewall-cmd", "--permanent", "--add-rich-rule", fmt.Sprintf("rule family=\"ipv4\" source address=\"%s\" port protocol=\"tcp\" port=\"%s\" accept", serverIP, exporterPort))
				d.exec.RunSudo(ctx, "firewall-cmd", "--reload")
			} else if _, _, checkErr := d.exec.RunSudo(ctx, "iptables", "--version"); checkErr == nil {
				d.exec.RunSudo(ctx, "iptables", "-I", "INPUT", "-p", "tcp", "--dport", exporterPort, "-s", serverIP, "-j", "ACCEPT")
				d.exec.RunSudo(ctx, "iptables", "-A", "INPUT", "-p", "tcp", "--dport", exporterPort, "-j", "DROP")
				d.persistIPTables(ctx)
			}
		}
	}

	setStep("Start service")
	if !shouldSkip("Start service") {
		// 8.5 Chown all system directories to the tantor user so the service can write to them
		chownDirs := []string{installPaths.BaseDir, installDir, dataDir}
		for _, dir := range []string{paths.LogDirs, paths.MetadataLogDir, paths.AppLogDir} {
			if dir != "" {
				chownDirs = append(chownDirs, strings.TrimSpace(dir))
			}
		}
		role := strings.TrimSpace(t.Parameters["service_role"])
		if role == "" {
			role = strings.TrimSpace(t.Parameters["role"])
		}
		if role == "zookeeper" || role == "broker_zookeeper" {
			chownDirs = append(chownDirs, zookeeperDataDir(t))
		}
		chownArgs := append([]string{"-R", "tantor:tantor"}, chownDirs...)
		if _, errOut, err := d.exec.RunSudo(ctx, "chown", chownArgs...); err != nil {
			log("Warning: Failed to chown system directories: %v (%s)", err, errOut)
		}

		_, _, err = d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
		if err == nil {
			_, _, err = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", serviceName)
			// Start exporter if it exists
			if _, _, checkErr := d.exec.RunSudo(ctx, "systemctl", "list-unit-files", serviceName+"-exporter.service"); checkErr == nil {
				_, _, _ = d.exec.RunSudo(ctx, "systemctl", "enable", "--now", serviceName+"-exporter.service")
			}
		}
		if err != nil {
			return logs.String(), fmt.Errorf("failed to start service: %w", err)
		}
		log("Kafka service %s started successfully", serviceName)
	} else {
		log("Skipping step (resume mode)")
	}

	setStep("Validate port")
	log("Validating ports in the next health checks...")

	setStep("Validate Kafka AdminClient connection")
	if deploymentModeForTask(t) != "zookeeper" && serviceNameForTask(t) != "controller" {
		log("Validating AdminClient connection...")
		listenerPort := t.Parameters["listener_port"]
		if listenerPort == "" {
			listenerPort = "9092"
		}
		// Try to wait a bit before connecting
		time.Sleep(5 * time.Second)
		topicScript := filepath.Join(activeInstallDir, "bin", "kafka-topics.sh")
		bootstrapServer := kafkaValidationBootstrapForTask(t, listenerPort)
		if _, validationErr := taskvalidate.HostPort(bootstrapServer); validationErr != nil {
			return logs.String(), fmt.Errorf("invalid Kafka validation bootstrap server: %w", validationErr)
		}
		out, errOut, err := d.exec.Run(ctx, "timeout", "20s", topicScript, "--list", "--bootstrap-server", bootstrapServer)
		if err != nil {
			log("Warning: AdminClient validation failed for %s (non-fatal): %v, out: %s, errOut: %s", bootstrapServer, err, out, errOut)
		} else {
			log("AdminClient successfully connected to broker at %s", bootstrapServer)
		}
	} else {
		log("Zookeeper or Controller-only mode — bypassing AdminClient connection")
	}

	setStep("Validate cluster health")
	if err := d.validateDeployment(ctx, t, activeInstallDir, &logs); err != nil {
		return logs.String(), fmt.Errorf("deployment validation failed: %w", err)
	}
	log("All deployment validations passed ✓")

	setStep("Mark DB state RUNNING")
	log("Deployment successfully completed. Emitting RUNNING state.")

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
	if deploymentModeForTask(t) == "zookeeper" {
		return d.validateZooKeeperDeployment(ctx, t, logs)
	}
	return d.validateKRaftDeployment(ctx, t, installDir, logs)
}

func (d *Deployer) validateKRaftDeployment(ctx context.Context, t *api.Task, installDir string, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	listenerPort := t.Parameters["listener_port"]
	if listenerPort == "" {
		listenerPort = "9092"
	}
	jmxMetricsPort := t.Parameters["jmx_port"]
	if jmxMetricsPort == "" {
		jmxMetricsPort = "7071"
	}
	var err error
	jmxMetricsPort, err = taskvalidate.Port(jmxMetricsPort)
	if err != nil {
		return fmt.Errorf("invalid jmx_port: %w", err)
	}

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
		out, _, _ := d.exec.Run(ctx, "pgrep", "-f", `kafka\.Kafka|kafka\.server\.KafkaRaftServer`)
		if strings.TrimSpace(out) != "" {
			log("  ✓ Kafka process detected (PID: %s)", strings.TrimSpace(out))
			goto check2
		}
		time.Sleep(3 * time.Second)
	}
	{
		journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceNameForTask(t), "-n", "50", "--no-pager")
		return fmt.Errorf("Kafka process not found after 30s. Logs:\n%s", journalOut)
	}

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
	metaPropertiesPath := filepath.Join(paths.MetaPropertiesDir, "meta.properties")
	metaExists, err := d.privilegedFileExists(ctx, metaPropertiesPath)
	if err != nil {
		return fmt.Errorf("failed to inspect KRaft meta.properties in %s: %w", paths.MetaPropertiesDir, err)
	}
	if !metaExists {
		return fmt.Errorf("KRaft meta.properties not found in %s", paths.MetaPropertiesDir)
	}
	log("  ✓ KRaft meta.properties exists")

	role, isBroker, isController := normalizeKRaftRole(t.Parameters["role"])
	controllerPort := t.Parameters["controller_port"]
	if controllerPort == "" {
		controllerPort = "9093"
	}

	log("Validation [4/6]: Checking service ports for %s...", role)
	portWait := kafkaPortValidationTimeout(t)
	if isBroker {
		if err := d.waitForListeningPort(ctx, listenerPort, portWait); err != nil {
			journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceNameForTask(t), "-n", "80", "--no-pager")
			return fmt.Errorf("broker port %s not listening after %s. Logs:\n%s", listenerPort, portWait, journalOut)
		}
		log("  Broker listening on port %s", listenerPort)
	}

	if isController {
		if err := d.waitForListeningPort(ctx, controllerPort, portWait); err != nil {
			journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceNameForTask(t), "-n", "80", "--no-pager")
			return fmt.Errorf("controller port %s not listening after %s. Logs:\n%s", controllerPort, portWait, journalOut)
		}
		log("  Controller listening on port %s", controllerPort)
	}

	jmxRequired := strings.EqualFold(strings.TrimSpace(firstNonEmpty(t.Parameters["jmx_required"], t.Parameters["jmxRequired"])), "true")
	jmxAgentPath := filepath.Join(installDir, "jmx", "jmx_prometheus_javaagent.jar")
	if !isUsableJar(jmxAgentPath) {
		if jmxRequired {
			return fmt.Errorf("JMX exporter is required by the deployment task but no valid jar is installed at %s", jmxAgentPath)
		}
		log("Validation [5/6]: JMX monitoring disabled; no JMX exporter validation required")
		log("Validation [6/6]: Metrics endpoint check skipped because JMX monitoring is disabled")
		return nil
	}

	log("Validation [5/6]: Checking JMX Exporter javaagent...")
	out2, _, _ := d.exec.Run(ctx, "pgrep", "-af", "jmx_prometheus_javaagent")
	if strings.Contains(out2, "jmx_prometheus_javaagent") {
		log("  JMX Prometheus Exporter attached")
	} else {
		if jmxRequired {
			journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceNameForTask(t), "-n", "80", "--no-pager")
			return fmt.Errorf("JMX exporter not attached to Kafka process. Logs:\n%s", journalOut)
		}
		log("  JMX exporter jar exists but javaagent is not attached; continuing because JMX is optional")
		return nil
	}

	log("Validation [6/6]: Checking metrics endpoint on port %s...", jmxMetricsPort)
	for i := 0; i < 5; i++ {
		_, _, err := d.exec.Run(ctx, "curl", "--silent", "--fail", "--max-time", "5", "http://localhost:"+jmxMetricsPort+"/metrics")
		if err == nil {
			log("  Metrics endpoint responding on port %s", jmxMetricsPort)
			return nil
		}
		time.Sleep(3 * time.Second)
	}
	if jmxRequired {
		journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceNameForTask(t), "-n", "80", "--no-pager")
		return fmt.Errorf("JMX metrics endpoint not responding on port %s. Logs:\n%s", jmxMetricsPort, journalOut)
	}
	log("  Metrics endpoint not responding on port %s; continuing because JMX is optional", jmxMetricsPort)

	exporterPort := t.Parameters["kafka_exporter_port"]
	if exporterPort == "" {
		exporterPort = "9308"
	}
	exporterPort, err = taskvalidate.Port(exporterPort)
	if err != nil {
		return fmt.Errorf("invalid kafka_exporter_port: %w", err)
	}

	// Optional validation for kafka_exporter
	log("Validation [7/7]: Checking kafka_exporter endpoint on port %s...", exporterPort)
	if _, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", serviceNameForTask(t)+"-exporter.service"); err == nil {
		for i := 0; i < 5; i++ {
			_, _, err := d.exec.Run(ctx, "curl", "--silent", "--fail", "--max-time", "5", "http://localhost:"+exporterPort+"/metrics")
			if err == nil {
				log("  kafka_exporter endpoint responding on port %s", exporterPort)
				return nil
			}
			time.Sleep(3 * time.Second)
		}
		log("  Warning: kafka_exporter service is active but metrics endpoint not responding on port %s", exporterPort)
	} else {
		log("  kafka_exporter service not active; skipping exporter validation")
	}

	return nil
}

func (d *Deployer) validateZooKeeperDeployment(ctx context.Context, t *api.Task, logs *strings.Builder) error {
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

	serviceName := serviceNameForTask(t)
	processPattern := "kafka.Kafka"
	processLabel := "Kafka broker"
	port := strings.TrimSpace(t.Parameters["listener_port"])
	portLabel := "broker"
	if serviceName == "zookeeper" {
		processPattern = "QuorumPeerMain"
		processLabel = "ZooKeeper"
		port = strings.TrimSpace(t.Parameters["zookeeper_port"])
		portLabel = "ZooKeeper client"
	}
	if port == "" {
		if serviceName == "zookeeper" {
			port = "2181"
		} else {
			port = "9092"
		}
	}

	log("Validation [1/4]: Checking %s process...", processLabel)
	for i := 0; i < 10; i++ {
		out, _, _ := d.exec.Run(ctx, "pgrep", "-f", processPattern)
		if strings.TrimSpace(out) != "" {
			log("  PASS: %s process detected (PID: %s)", processLabel, strings.TrimSpace(out))
			goto serviceCheck
		}
		time.Sleep(3 * time.Second)
	}
	{
		journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceName, "-n", "50", "--no-pager")
		return fmt.Errorf("%s process not found after 30s. Logs:\n%s", processLabel, journalOut)
	}

serviceCheck:
	log("Validation [2/4]: Checking %s.service...", serviceName)
	out, _, err := d.exec.RunSudo(ctx, "systemctl", "is-active", serviceName)
	if err != nil || strings.TrimSpace(out) != "active" {
		return fmt.Errorf("%s.service is not active: %s", serviceName, out)
	}
	log("  PASS: %s.service is active", serviceName)

	log("Validation [3/4]: Checking %s port %s...", portLabel, port)
	if err := d.waitForListeningPort(ctx, port, kafkaPortValidationTimeout(t)); err != nil {
		return err
	}
	log("  PASS: %s port %s is listening", portLabel, port)

	log("Validation [4/4]: Checking mode-specific configuration...")
	if serviceName == "zookeeper" {
		dataDir := zookeeperDataDir(t)
		if _, err := os.Stat(filepath.Join(dataDir, "myid")); err != nil {
			return fmt.Errorf("ZooKeeper myid file not found in %s", dataDir)
		}
		log("  PASS: ZooKeeper myid is present")
		return nil
	}
	if strings.TrimSpace(t.Parameters["zookeeper_connect"]) == "" {
		return fmt.Errorf("zookeeper_connect is missing from broker deployment")
	}
	log("  PASS: ZooKeeper connection string is configured")
	return nil
}

func (d *Deployer) waitForListeningPort(ctx context.Context, port string, timeout time.Duration) error {
	validPort, err := taskvalidate.Port(port)
	if err != nil {
		return err
	}
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		connection, dialErr := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", validPort), 2*time.Second)
		if dialErr == nil {
			_ = connection.Close()
			return nil
		}
		time.Sleep(3 * time.Second)
	}
	return fmt.Errorf("port %s not listening after %s", port, timeout)
}

func kafkaPortValidationTimeout(t *api.Task) time.Duration {
	raw := firstNonEmpty(
		t.Parameters["kafka_port_wait_seconds"],
		t.Parameters["port_validation_timeout_seconds"],
		t.Parameters["service_start_timeout_seconds"],
	)
	seconds := 120
	if raw != "" {
		if parsed, err := strconv.Atoi(raw); err == nil {
			seconds = parsed
		}
	}
	if seconds < 30 {
		seconds = 30
	}
	if seconds > 600 {
		seconds = 600
	}
	return time.Duration(seconds) * time.Second
}

func kafkaNodeAddressForTask(t *api.Task) string {
	return firstNonEmpty(
		t.Parameters["advertised_host"],
		t.Parameters["advertised_hostname"],
		t.Parameters["host_ip"],
		t.Parameters["node_ip"],
		t.Parameters["ip_address"],
		t.Parameters["host_hostname"],
		t.Parameters["hostname"],
		getLocalIP(),
	)
}

func kafkaValidationBootstrapForTask(t *api.Task, listenerPort string) string {
	configured := firstNonEmpty(
		t.Parameters["validation_bootstrap_server"],
		t.Parameters["admin_bootstrap_server"],
	)
	if configured != "" {
		return configured
	}
	return fmt.Sprintf("%s:%s", kafkaNodeAddressForTask(t), listenerPort)
}

func deploymentModeForTask(t *api.Task) string {
	if strings.EqualFold(strings.TrimSpace(t.Parameters["mode"]), "zookeeper") {
		return "zookeeper"
	}
	return "kraft"
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
		return "broker,controller", true, true
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

func kafkaArtifactWorkDir(t *api.Task, fallback string) string {
	// Artifact bytes are agent runtime state, not Kafka installation content.
	// Always keep downloads inside the configured agent-owned artifacts root.
	// Older backends may send artifact_load_dir/artifacts_dir values such as
	// /srv/tantor-agent that the limited service account cannot create or write.
	// Such paths must never override the local agent runtime directory.
	base := filepath.Clean(strings.TrimSpace(fallback))
	if base == "." || base == "" {
		base = filepath.Join(os.TempDir(), "tantor-agent", "artifacts")
	}

	configured := strings.TrimSpace(t.Parameters["artifact_load_dir"])
	if configured == "" {
		configured = strings.TrimSpace(t.Parameters["artifacts_dir"])
	}
	if configured == "" {
		return base
	}

	candidate := filepath.Clean(configured)
	rel, err := filepath.Rel(base, candidate)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return base
	}
	return candidate
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
		paths.LogDirs = strings.TrimSpace(t.Parameters["log_dirs"])
		if paths.LogDirs == "" {
			paths.LogDirs = filepath.Join(dataDir, "controller-data", "logs")
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
		serviceName = strings.TrimSuffix(serviceName, ".service")
		if validated, err := taskvalidate.Identifier(serviceName, "systemd service",
			"kafka", "broker", "controller", "zookeeper",
		); err == nil {
			return validated
		}
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
	configRoot := filepath.Join(installDir, "config", "kraft")
	if usesFlatKafkaConfigLayout(t.Parameters["version"]) {
		configRoot = filepath.Join(installDir, "config")
	}
	configured := strings.TrimSpace(t.Parameters["config_path"])
	if configured != "" {
		if filepath.IsAbs(configured) {
			return configured
		}
		return filepath.Join(configRoot, configured)
	}

	configured = strings.TrimSpace(t.Parameters["config_file"])
	if configured != "" {
		if filepath.IsAbs(configured) {
			return configured
		}
		if configured == "zookeeper.properties" || configured == "server.properties" {
			return filepath.Join(installDir, "config", configured)
		}
		return filepath.Join(configRoot, configured)
	}

	switch serviceNameForTask(t) {
	case "controller":
		return filepath.Join(configRoot, "controller.properties")
	case "broker":
		return filepath.Join(configRoot, "broker.properties")
	case "zookeeper":
		return filepath.Join(installDir, "config", "zookeeper.properties")
	default:
		return filepath.Join(configRoot, "server.properties")
	}
}

func usesFlatKafkaConfigLayout(version string) bool {
	version = strings.TrimSpace(strings.TrimPrefix(version, "v"))
	majorText := strings.SplitN(version, ".", 2)[0]
	major, err := strconv.Atoi(majorText)
	return err == nil && major >= 4
}
func (d *Deployer) generateConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	if deploymentModeForTask(t) == "zookeeper" {
		return d.generateZooKeeperConfigs(ctx, t, installDir, dataDir)
	}
	return d.generateKRaftConfigs(ctx, t, installDir, dataDir)
}

func (d *Deployer) generateKRaftConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	nodeId := t.Parameters["node_id"]
	if nodeId == "" {
		nodeId = "1"
	}
	hostname := kafkaNodeAddressForTask(t)
	quorumMode := strings.ToLower(strings.TrimSpace(t.Parameters["kraft_quorum_mode"]))
	if quorumMode == "" {
		quorumMode = "static"
	}
	if quorumMode != "static" && quorumMode != "dynamic" {
		return fmt.Errorf("unsupported KRaft quorum mode %q", quorumMode)
	}
	quorumVoters := firstNonEmpty(t.Parameters["quorum_voters"], t.Parameters["controller_quorum_voters"])
	quorumBootstrap := firstNonEmpty(
		t.Parameters["quorum_bootstrap_servers"],
		t.Parameters["controller_quorum_bootstrap_servers"],
		t.Parameters["controller_endpoints"],
	)
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
	if quorumMode == "static" && quorumVoters == "" {
		quorumVoters = fmt.Sprintf("%s@%s:%s", nodeId, hostname, controllerPort)
	}
	if quorumBootstrap == "" {
		quorumBootstrap = quorumBootstrapServers(quorumVoters)
	}
	if quorumMode == "dynamic" && quorumBootstrap == "" {
		return fmt.Errorf("quorum_bootstrap_servers is required for dynamic KRaft quorum")
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
	minInsyncReplicas := t.Parameters["min_insync_replicas"]
	if minInsyncReplicas == "" {
		minInsyncReplicas = "1"
	}

	props := struct {
		NodeId                 string
		QuorumMode             string
		QuorumVoters           string
		QuorumBootstrapServers string
		Hostname               string
		LogDirs                string
		MetadataLogDir         string
		Role                   string
		Listeners              string
		AdvertisedListeners    string
		ListenerPort           string
		ControllerPort         string
		NumPartitions          string
		RepFactor              string
		MinInsyncReplicas      string
		IsBroker               bool
	}{
		NodeId:                 nodeId,
		QuorumMode:             quorumMode,
		QuorumVoters:           quorumVoters,
		QuorumBootstrapServers: quorumBootstrap,
		Hostname:               hostname,
		LogDirs:                paths.LogDirs,
		MetadataLogDir:         paths.MetadataLogDir,
		Role:                   role,
		Listeners:              listeners,
		AdvertisedListeners:    advertisedListeners,
		ListenerPort:           listenerPort,
		ControllerPort:         controllerPort,
		NumPartitions:          numPartitions,
		RepFactor:              repFactor,
		MinInsyncReplicas:      minInsyncReplicas,
		IsBroker:               isBroker,
	}

	if customTemplate := customPropertiesTemplateForTask(t); strings.TrimSpace(customTemplate) != "" {
		content := mergeCustomKafkaProperties(customTemplate, map[string]string{
			"process.roles":                       role,
			"node.id":                             nodeId,
			"broker.id":                           ternaryString(isBroker, nodeId, ""),
			"controller.listener.names":           "CONTROLLER",
			"listeners":                           listeners,
			"advertised.listeners":                advertisedListeners,
			"listener.security.protocol.map":      "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT",
			"inter.broker.listener.name":          ternaryString(isBroker, "PLAINTEXT", ""),
			"controller.quorum.voters":            ternaryString(quorumMode == "static", quorumVoters, ""),
			"controller.quorum.bootstrap.servers": ternaryString(quorumMode == "dynamic", quorumBootstrap, ""),
			"log.dirs":                            paths.LogDirs,
			"metadata.log.dir":                    paths.MetadataLogDir,
		})
		return d.writeStringToSudoFile(ctx, content, configPathForTask(installDir, t))
	}

	return d.writeTemplateToSudoFile(ctx, ServerPropertiesTemplate, props, configPathForTask(installDir, t))
}

func (d *Deployer) generateZooKeeperConfigs(ctx context.Context, t *api.Task, installDir, dataDir string) error {
	role := strings.TrimSpace(t.Parameters["service_role"])
	if role == "" {
		role = strings.TrimSpace(t.Parameters["role"])
	}

	if role == "zookeeper" || role == "broker_zookeeper" {
		zkDataDir := zookeeperDataDir(t)
		if d.exec == nil {
			if err := os.MkdirAll(zkDataDir, 0755); err != nil {
				return fmt.Errorf("failed to create ZooKeeper data directory: %w", err)
			}
		} else if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", zkDataDir); err != nil {
			return fmt.Errorf("failed to create ZooKeeper data directory: %w (%s)", err, errOut)
		}
		clientPort := strings.TrimSpace(t.Parameters["zookeeper_port"])
		if clientPort == "" {
			clientPort = "2181"
		}
		servers := strings.TrimSpace(t.Parameters["zookeeper_servers"])
		configPath := configPathForTask(installDir, t)
		customTemplate := strings.TrimSpace(t.Parameters["zookeeper_properties_template"])
		if customTemplate != "" {
			content := strings.TrimRight(strings.ReplaceAll(customTemplate, "\r\n", "\n"), "\n")
			content += "\n\n# ---- Tantor generated ZooKeeper overrides ----\n"
			content += "dataDir=" + zkDataDir + "\n"
			content += "clientPort=" + clientPort + "\n"
			if servers != "" {
				content += servers + "\n"
			}
			if err := d.writeStringToSudoFile(ctx, content, configPath); err != nil {
				return err
			}
		} else {
			props := struct {
				DataDir    string
				ClientPort string
				Servers    string
			}{DataDir: zkDataDir, ClientPort: clientPort, Servers: servers}
			if err := d.writeTemplateToSudoFile(ctx, ZooKeeperPropertiesTemplate, props, configPath); err != nil {
				return err
			}
		}
		return d.writeStringToSudoFile(ctx, strings.TrimSpace(t.Parameters["node_id"]), filepath.Join(zkDataDir, "myid"))
	}

	if role != "broker" {
		return fmt.Errorf("unsupported ZooKeeper deployment role %q; broker and ZooKeeper must be separate service assignments", role)
	}

	nodeID := strings.TrimSpace(t.Parameters["node_id"])
	if nodeID == "" {
		nodeID = "1"
	}
	listenerPort := strings.TrimSpace(t.Parameters["listener_port"])
	if listenerPort == "" {
		listenerPort = "9092"
	}
	zookeeperConnect := strings.TrimSpace(t.Parameters["zookeeper_connect"])
	if zookeeperConnect == "" {
		return fmt.Errorf("zookeeper_connect is required for a ZooKeeper-backed broker")
	}
	numPartitions := firstNonBlank(t.Parameters["num_partitions"], "1")
	repFactor := firstNonBlank(t.Parameters["replication_factor"], "1")
	minISR := firstNonBlank(t.Parameters["min_insync_replicas"], "1")
	paths := resolveKafkaRolePaths(t, installDir, dataDir)
	hostname := kafkaNodeAddressForTask(t)

	customTemplate := strings.TrimSpace(t.Parameters["broker_properties_template"])
	if customTemplate == "" {
		customTemplate = strings.TrimSpace(t.Parameters["server_properties_template"])
	}
	if customTemplate != "" {
		content := mergeCustomKafkaProperties(customTemplate, map[string]string{
			"broker.id":                      nodeID,
			"listeners":                      fmt.Sprintf("PLAINTEXT://%s:%s", hostname, listenerPort),
			"advertised.listeners":           fmt.Sprintf("PLAINTEXT://%s:%s", hostname, listenerPort),
			"listener.security.protocol.map": "PLAINTEXT:PLAINTEXT",
			"inter.broker.listener.name":     "PLAINTEXT",
			"zookeeper.connect":              zookeeperConnect,
			"log.dirs":                       paths.LogDirs,
		})
		return d.writeStringToSudoFile(ctx, content, configPathForTask(installDir, t))
	}

	props := struct {
		NodeId            string
		Hostname          string
		ListenerPort      string
		ZooKeeperConnect  string
		LogDirs           string
		MetadataLogDir    string
		NumPartitions     string
		RepFactor         string
		MinInsyncReplicas string
	}{
		NodeId: nodeID, Hostname: hostname, ListenerPort: listenerPort,
		ZooKeeperConnect: zookeeperConnect, LogDirs: paths.LogDirs,
		NumPartitions: numPartitions, RepFactor: repFactor, MinInsyncReplicas: minISR,
	}
	return d.writeTemplateToSudoFile(ctx, ZooKeeperBrokerPropertiesTemplate, props, configPathForTask(installDir, t))
}

func zookeeperDataDir(t *api.Task) string {
	if configured := strings.TrimSpace(t.Parameters["zookeeper_data_dir"]); configured != "" {
		return configured
	}
	base := strings.TrimSpace(t.Parameters["kafka_data_dir"])
	if base == "" {
		base = defaultKafkaDataDir(resolveKafkaInstallPaths(t).BaseDir)
	}
	return filepath.Join(base, "zookeeper-data")
}

func firstNonBlank(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}

func customPropertiesTemplateForTask(t *api.Task) string {
	role := strings.TrimSpace(t.Parameters["service_role"])
	if role == "" {
		role = strings.TrimSpace(t.Parameters["role"])
	}
	switch role {
	case "controller":
		return t.Parameters["controller_properties_template"]
	case "broker":
		return t.Parameters["broker_properties_template"]
	case "broker_controller", "":
		return t.Parameters["server_properties_template"]
	default:
		if strings.Contains(role, "broker") && strings.Contains(role, "controller") {
			return t.Parameters["server_properties_template"]
		}
		return ""
	}
}

func mergeCustomKafkaProperties(base string, overrides map[string]string) string {
	var out strings.Builder
	normalized := strings.ReplaceAll(base, "\r\n", "\n")
	for _, line := range strings.Split(normalized, "\n") {
		trimmed := strings.TrimSpace(line)
		if trimmed != "" && !strings.HasPrefix(trimmed, "#") && !strings.HasPrefix(trimmed, "!") {
			if separator := strings.Index(trimmed, "="); separator >= 0 {
				key := strings.TrimSpace(trimmed[:separator])
				if _, managed := overrides[key]; managed {
					continue
				}
			}
		}
		out.WriteString(line)
		out.WriteString("\n")
	}
	trimmedBase := strings.TrimRight(out.String(), "\n")
	out.Reset()
	out.WriteString(trimmedBase)
	out.WriteString("\n\n# ---- Tantor generated deployment overrides ----\n")
	for _, key := range orderedKafkaOverrideKeys() {
		value, ok := overrides[key]
		if !ok || strings.TrimSpace(value) == "" {
			continue
		}
		out.WriteString(key)
		out.WriteString("=")
		out.WriteString(value)
		out.WriteString("\n")
	}
	return out.String()
}

func (d *Deployer) privilegedFileExists(ctx context.Context, path string) (bool, error) {
	if d == nil || d.exec == nil {
		_, err := os.Stat(path)
		if err == nil {
			return true, nil
		}
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, err
	}

	_, stderr, err := d.exec.RunSudo(ctx, "test", "-f", path)
	if err == nil {
		return true, nil
	}
	// `test -f` returns exit status 1 when the file is absent. The executor wraps
	// that status as an error, so verify the parent path is inspectable before
	// treating it as a clean "not found" result. This keeps sudo/policy failures
	// distinguishable from a fresh KRaft deployment.
	parent := filepath.Dir(path)
	if filepath.VolumeName(path) == "" && (strings.HasPrefix(path, "/") || strings.HasPrefix(path, `\`)) {
		parent = stdpath.Dir(strings.ReplaceAll(path, `\`, "/"))
	}
	if _, parentErrOut, parentErr := d.exec.RunSudo(ctx, "test", "-d", parent); parentErr != nil {
		detail := strings.TrimSpace(strings.TrimSpace(stderr) + " " + strings.TrimSpace(parentErrOut))
		if detail == "" {
			detail = parentErr.Error()
		}
		return false, fmt.Errorf("cannot inspect parent directory %s: %s", parent, detail)
	}
	return false, nil
}

func validateMetaProperties(ctx context.Context, d *Deployer, dirs []string, expectedClusterID, expectedNodeID string, validateNodeID bool) error {
	seen := make(map[string]bool)
	for _, dir := range dirs {
		if dir == "" || seen[dir] {
			continue
		}
		seen[dir] = true

		metaPropsPath := filepath.Join(dir, "meta.properties")
		content, err := os.ReadFile(metaPropsPath)
		if err != nil {
			// Try reading with sudo if normal read fails
			out, _, err2 := d.exec.RunSudo(ctx, "cat", metaPropsPath)
			if err2 != nil {
				continue // File doesn't exist or is not readable
			}
			content = []byte(out)
		}

		values := make(map[string]string)
		for _, line := range strings.Split(strings.ReplaceAll(string(content), "\r\n", "\n"), "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, "!") {
				continue
			}
			separator := strings.Index(line, "=")
			if separator < 0 {
				continue
			}
			values[strings.TrimSpace(line[:separator])] = strings.TrimSpace(line[separator+1:])
		}

		if values["cluster.id"] != "" && values["cluster.id"] != expectedClusterID {
			return fmt.Errorf("Invalid cluster.id in: %s. Expected new cluster ID %q, but read old cluster ID %q.", metaPropsPath, expectedClusterID, values["cluster.id"])
		}
		if validateNodeID && values["node.id"] != "" && values["node.id"] != expectedNodeID {
			return fmt.Errorf("Invalid node.id in: %s. Expected new node ID %q, but read old node ID %q.", metaPropsPath, expectedNodeID, values["node.id"])
		}
	}
	return nil
}

func orderedKafkaOverrideKeys() []string {
	return []string{
		"process.roles",
		"node.id",
		"broker.id",
		"zookeeper.connect",
		"controller.listener.names",
		"listeners",
		"advertised.listeners",
		"listener.security.protocol.map",
		"inter.broker.listener.name",
		"controller.quorum.voters",
		"controller.quorum.bootstrap.servers",
		"log.dirs",
		"metadata.log.dir",
	}
}

func quorumBootstrapServers(quorumVoters string) string {
	parts := strings.Split(quorumVoters, ",")
	servers := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if idx := strings.Index(part, "@"); idx >= 0 && idx+1 < len(part) {
			servers = append(servers, part[idx+1:])
		}
	}
	return strings.Join(servers, ",")
}

func ternaryString(condition bool, yes, no string) string {
	if condition {
		return yes
	}
	return no
}

func (d *Deployer) UpdateConfig(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	installPaths := resolveKafkaInstallPaths(t)
	installDir := installPaths.ActiveDir
	configPath := configPathForTask(installDir, t)
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}

	backupPath, err := d.backupConfig(ctx, t, configPath)
	if err != nil {
		return logs.String(), err
	}
	if backupPath != "" {
		logs.WriteString(fmt.Sprintf("Existing config backed up to %s\n", backupPath))
	}
	if err := d.generateConfigs(ctx, t, installDir, dataDir); err != nil {
		d.restoreConfigBackup(ctx, backupPath, configPath, &logs)
		return logs.String(), err
	}
	logs.WriteString("Configs updated successfully\n")

	// Restart if requested
	if t.Parameters["restart"] == "true" {
		_, _, err := d.exec.RunSudo(ctx, "systemctl", "restart", serviceNameForTask(t))
		if err != nil {
			d.restoreConfigBackup(ctx, backupPath, configPath, &logs)
			_, _, _ = d.exec.RunSudo(ctx, "systemctl", "restart", serviceNameForTask(t))
			return logs.String(), fmt.Errorf("failed to restart kafka: %w", err)
		}
		logs.WriteString(fmt.Sprintf("Kafka service %s restarted\n", serviceNameForTask(t)))
	}

	return logs.String(), nil
}

func (d *Deployer) backupConfig(ctx context.Context, t *api.Task, configPath string) (string, error) {
	if _, _, err := d.exec.RunSudo(ctx, "test", "-f", configPath); err != nil {
		return "", fmt.Errorf("active configuration file does not exist at %s; no changes were applied", configPath)
	}
	version := strings.TrimSpace(t.Parameters["config_version"])
	if version == "" {
		version = "unversioned"
	}
	version = strings.NewReplacer("/", "_", "\\", "_", ":", "_", " ", "_").Replace(version)
	backupDir := filepath.Join(filepath.Dir(configPath), ".tantor-backups", filepath.Base(configPath))
	backupName := fmt.Sprintf("v%s-%s.bak", version, time.Now().UTC().Format("20060102T150405.000000000Z"))
	backupPath := filepath.Join(backupDir, backupName)
	if out, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", backupDir); err != nil {
		return "", fmt.Errorf("failed to create config backup directory: %w (%s %s)", err, out, errOut)
	}
	if out, errOut, err := d.exec.RunSudo(ctx, "cp", "-p", configPath, backupPath); err != nil {
		return "", fmt.Errorf("failed to back up existing config: %w (%s %s)", err, out, errOut)
	}
	return backupPath, nil
}

func (d *Deployer) restoreConfigBackup(ctx context.Context, backupPath, configPath string, logs *strings.Builder) {
	if backupPath == "" {
		logs.WriteString("No previous config file was available for automatic restore\n")
		return
	}
	if out, errOut, err := d.exec.RunSudo(ctx, "cp", "-p", backupPath, configPath); err != nil {
		logs.WriteString(fmt.Sprintf("Automatic config restore failed: %v (%s %s)\n", err, out, errOut))
		return
	}
	logs.WriteString(fmt.Sprintf("Previous config restored automatically from %s\n", backupPath))
}

func (d *Deployer) Upgrade(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	if err := validateCommandTaskInputs(t); err != nil {
		return logs.String(), err
	}
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

func validateCommandTaskInputs(t *api.Task) error {
	if t == nil {
		return fmt.Errorf("deployment task is required")
	}
	for _, key := range []string{"listener_port", "controller_port", "jmx_port", "kafka_exporter_port", "zookeeper_port"} {
		if value := strings.TrimSpace(t.Parameters[key]); value != "" {
			if _, err := taskvalidate.Port(value); err != nil {
				return fmt.Errorf("invalid %s: %w", key, err)
			}
		}
	}
	for _, key := range []string{"kafka_install_base_dir", "kafka_install_dir", "kafka_data_dir", "parcel_dir"} {
		if value := strings.TrimSpace(t.Parameters[key]); value != "" {
			if _, err := taskvalidate.ApprovedPath(value); err != nil {
				return fmt.Errorf("invalid %s: %w", key, err)
			}
		}
	}
	for _, key := range []string{"service_role", "role"} {
		if value := strings.TrimSpace(t.Parameters[key]); value != "" {
			if _, err := taskvalidate.Identifier(value, key,
				"broker", "controller", "broker_controller", "broker,controller", "broker_zookeeper", "zookeeper",
			); err != nil {
				return err
			}
		}
	}
	for _, key := range []string{"systemd_service", "service_name"} {
		if value := strings.TrimSuffix(strings.TrimSpace(t.Parameters[key]), ".service"); value != "" {
			if _, err := taskvalidate.Identifier(value, key, "kafka", "broker", "controller", "zookeeper"); err != nil {
				return err
			}
		}
	}
	if value := strings.TrimSpace(t.Parameters["mode"]); value != "" {
		if _, err := taskvalidate.Identifier(strings.ToLower(value), "mode", "kraft", "zookeeper"); err != nil {
			return err
		}
	}
	for _, key := range []string{"validation_bootstrap_server", "admin_bootstrap_server"} {
		if value := strings.TrimSpace(t.Parameters[key]); value != "" {
			if _, err := taskvalidate.HostPort(value); err != nil {
				return fmt.Errorf("invalid %s: %w", key, err)
			}
		}
	}
	return nil
}

func (d *Deployer) normalizeKafkaTreePermissions(ctx context.Context, installDir string) error {
	cleanDir, pathErr := taskvalidate.ApprovedPath(strings.TrimSpace(installDir))
	if pathErr != nil {
		return fmt.Errorf("refusing to normalize unsafe Kafka install directory %q: %w", installDir, pathErr)
	}
	return d.normalizeKafkaTreePermissionsAt(ctx, cleanDir)
}

func (d *Deployer) normalizeKafkaTreePermissionsAt(ctx context.Context, cleanDir string) error {
	// `chmod -R a+rX` keeps data/config files non-executable while ensuring all
	// directories are traversable and already-executable files stay executable.
	// The second command explicitly restores executable bits on Kafka shell
	// launchers in case the uploaded tarball lost them during packaging/upload.
	out, errOut, err := d.exec.RunSudo(ctx, "test", "-d", cleanDir)
	if err == nil {
		out, errOut, err = d.exec.RunSudo(ctx, "chmod", "-R", "a+rX", "--", cleanDir)
	}
	if err == nil {
		binDir := filepath.Join(cleanDir, "bin")
		if _, _, testErr := d.exec.RunSudo(ctx, "test", "-d", binDir); testErr == nil {
			out, errOut, err = d.exec.RunSudo(ctx, "find", binDir, "-type", "f", "-name", "*.sh", "-exec", "chmod", "a+rx", "{}", "+")
		}
	}
	if err != nil {
		detail := strings.TrimSpace(strings.TrimSpace(out) + "\n" + strings.TrimSpace(errOut))
		if detail == "" {
			detail = err.Error()
		}
		return fmt.Errorf("failed to normalize Kafka runtime permissions under %s: %s", cleanDir, detail)
	}

	for _, required := range []string{
		filepath.Join(cleanDir, "bin", "kafka-server-start.sh"),
		filepath.Join(cleanDir, "bin", "kafka-storage.sh"),
	} {
		if _, stderr, testErr := d.exec.RunSudo(ctx, "test", "-r", required); testErr != nil {
			detail := strings.TrimSpace(stderr)
			if detail == "" {
				detail = testErr.Error()
			}
			return fmt.Errorf("required Kafka launcher is not readable after permission normalization: %s: %s", required, detail)
		}
	}
	return nil
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
	cleanTarget, pathErr := taskvalidate.ApprovedPath(strings.TrimSpace(targetDir))
	if pathErr != nil {
		return fmt.Errorf("invalid target Kafka directory: %w", pathErr)
	}
	targetDir = cleanTarget
	startScript := filepath.Join(targetDir, "bin", "kafka-server-start.sh")
	if _, err := os.Stat(startScript); err == nil {
		log("Kafka target binaries already staged at %s", targetDir)
		_, _, _ = d.exec.RunSudo(ctx, "find", filepath.Join(targetDir, "bin"), "-type", "f", "-name", "*.sh", "-exec", "chmod", "a+x", "{}", "+")
		return nil
	}

	parcelDir := strings.TrimSpace(t.Parameters["parcel_dir"])
	if parcelDir == "" {
		return fmt.Errorf("active parcel directory is required for upgrade; distribute and activate the target Kafka parcel first")
	}
	parcelDir, pathErr = taskvalidate.ApprovedPath(parcelDir)
	if pathErr != nil {
		return fmt.Errorf("invalid active parcel directory: %w", pathErr)
	}

	log("Staging Kafka binaries from active parcel: %s", parcelDir)
	commands := []struct {
		name string
		args []string
	}{
		{"test", []string{"-d", parcelDir}},
		{"rm", []string{"-rf", "--", targetDir}},
		{"mkdir", []string{"-p", "--", filepath.Dir(targetDir), targetDir}},
		{"cp", []string{"-a", "--", filepath.Join(parcelDir, "."), targetDir + string(filepath.Separator)}},
		{"chmod", []string{"-R", "a+rX", "--", targetDir}},
		{"find", []string{filepath.Join(targetDir, "bin"), "-type", "f", "-name", "*.sh", "-exec", "chmod", "a+x", "{}", "+"}},
	}
	for _, command := range commands {
		if out, errOut, err := d.exec.RunSudo(ctx, command.name, command.args...); err != nil {
			return fmt.Errorf("failed to stage target Kafka binaries with %s: %w, out: %s, err: %s", command.name, err, out, errOut)
		}
	}
	log("Kafka target binaries staged at %s", targetDir)
	return nil
}

func (d *Deployer) ensureKafkaBinaryVersion(ctx context.Context, installDir, expectedVersion string, log func(string, ...interface{})) error {
	cleanDir, pathErr := taskvalidate.ApprovedPath(strings.TrimSpace(installDir))
	if pathErr != nil {
		return fmt.Errorf("invalid Kafka install directory: %w", pathErr)
	}
	versionScript := filepath.Join(cleanDir, "bin", "kafka-topics.sh")
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

func isUsableJar(path string) bool {
	data, err := os.ReadFile(path)
	return err == nil && len(data) >= 2 && data[0] == 'P' && data[1] == 'K'
}

func (d *Deployer) persistIPTables(ctx context.Context) {
	content, _, err := d.exec.RunSudo(ctx, "iptables-save")
	if err != nil {
		return
	}
	tempFile, err := os.CreateTemp("", "tantor-iptables-*")
	if err != nil {
		return
	}
	tempName := tempFile.Name()
	defer os.Remove(tempName)
	if _, err = tempFile.WriteString(content + "\n"); err != nil {
		_ = tempFile.Close()
		return
	}
	if err = tempFile.Close(); err != nil {
		return
	}
	if _, _, err = d.exec.RunSudo(ctx, "install", "-m", "0600", "--", tempName, "/etc/sysconfig/iptables"); err != nil {
		_, _, _ = d.exec.RunSudo(ctx, "install", "-m", "0600", "--", tempName, "/etc/iptables/rules.v4")
	}
}

func (d *Deployer) createSystemdService(ctx context.Context, user, installDir string, t *api.Task) error {
	out, _, _ := d.exec.Run(ctx, "readlink", "-f", "/usr/bin/java")
	javaHome := filepath.Dir(filepath.Dir(strings.TrimSpace(out)))
	if javaHome == "" || javaHome == "." {
		javaHome = "/usr" // fallback
	}

	heapSize := t.Parameters["heap_size"]
	if heapSize == "" {
		heapSize = "1G"
	}

	jmxPort := t.Parameters["jmx_port"]
	if jmxPort == "" {
		jmxPort = "7071"
	}
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		installPaths := resolveKafkaInstallPaths(t)
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}
	paths := resolveKafkaRolePaths(t, installDir, dataDir)

	serviceName := serviceNameForTask(t)
	jmxAgentPath := filepath.Join(installDir, "jmx", "jmx_prometheus_javaagent.jar")
	jmxConfigPath := filepath.Join(installDir, "jmx", "jmx_config.yml")
	_, _, _ = normalizeKRaftRole(t.Parameters["role"])
	if serviceName == "controller" {
		jmxPort = ""
		jmxAgentPath = ""
		jmxConfigPath = ""
	} else if !isUsableJar(jmxAgentPath) {
		// JMX monitoring is optional unless the deployment task explicitly requires it.
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

	serviceTemplate := SystemdTemplate
	if serviceName == "zookeeper" {
		serviceTemplate = ZooKeeperSystemdTemplate
	}
	d.exec.RunSudo(ctx, "rm", "-rf", filepath.Join("/etc/systemd/system", serviceName+".service.d"))
	if err := d.writeTemplateToSudoFile(ctx, serviceTemplate, props, filepath.Join("/etc/systemd/system", serviceName+".service")); err != nil {
		return err
	}

	// Create exporter service if binary exists
	_, isBroker, _ := normalizeKRaftRole(t.Parameters["role"])
	if isBroker || serviceName != "controller" {
		kafkaExporterPath := filepath.Join(installDir, "bin", "kafka_exporter")
		if _, _, checkErr := d.exec.RunSudo(ctx, "test", "-x", kafkaExporterPath); checkErr == nil {
			exporterPort := t.Parameters["kafka_exporter_port"]
			if exporterPort == "" {
				exporterPort = "9308"
			}

			listenerPort := t.Parameters["listener_port"]
			if listenerPort == "" {
				listenerPort = "9092"
			}

			targetHost := strings.TrimSpace(t.Parameters["advertised_address"])
			if targetHost == "" {
				targetHost = strings.TrimSpace(t.Parameters["bind_address"])
			}
			if targetHost == "" {
				targetHost = "localhost"
			}

			exporterProps := struct {
				User         string
				Group        string
				InstallDir   string
				ExporterPort string
				KafkaPort    string
				Hostname     string
			}{
				User:         user,
				Group:        user,
				InstallDir:   installDir,
				ExporterPort: exporterPort,
				KafkaPort:    listenerPort,
				Hostname:     targetHost,
			}
			if err := d.writeTemplateToSudoFile(ctx, KafkaExporterSystemdTemplate, exporterProps, filepath.Join("/etc/systemd/system", serviceName+"-exporter.service")); err != nil {
				return err
			}
		}
	}
	return nil
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

	return d.writeStringToSudoFile(ctx, buf.String(), dest)
}

func (d *Deployer) writeStringToSudoFile(ctx context.Context, content string, dest string) error {
	// A nil executor is used by pure configuration-generation unit tests.
	// Production deployers are always constructed with an executor.
	if d.exec == nil {
		content = strings.ReplaceAll(content, "\r\n", "\n")
		if !strings.HasSuffix(content, "\n") {
			content += "\n"
		}
		if err := os.MkdirAll(filepath.Dir(dest), 0755); err != nil {
			return err
		}
		return os.WriteFile(dest, []byte(content), 0644)
	}

	if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", filepath.Dir(dest)); err != nil {
		return fmt.Errorf("failed to create dir %s: %w (%s)", filepath.Dir(dest), err, errOut)
	}

	content = strings.ReplaceAll(content, "\r\n", "\n")
	if !strings.HasSuffix(content, "\n") {
		content += "\n"
	}

	tmpFile := filepath.Join(os.TempDir(), "tantor_cfg_"+time.Now().Format("150405.0000"))
	if err := os.WriteFile(tmpFile, []byte(content), 0644); err != nil {
		return fmt.Errorf("failed to write tmp config: %w", err)
	}
	defer os.Remove(tmpFile)

	if _, errOut, err := d.exec.RunSudo(ctx, "mv", tmpFile, dest); err != nil {
		return fmt.Errorf("failed to move config to %s: %w (%s)", dest, err, errOut)
	}
	d.exec.RunSudo(ctx, "chmod", "644", dest)

	return nil
}

func (d *Deployer) Rollback(ctx context.Context, t *api.Task) (string, error) {
	var logs strings.Builder
	log := func(msg string, args ...interface{}) {
		formatted := fmt.Sprintf(msg, args...)
		logs.WriteString(formatted + "\n")
		slog.Info(formatted)
	}

	log("Starting Kafka rollback process...")

	log("Stopping Kafka systemd services...")
	for _, service := range []string{"broker", "controller", "kafka", "zookeeper", "broker-exporter", "controller-exporter", "kafka-exporter", "zookeeper-exporter"} {
		d.exec.RunSudo(ctx, "systemctl", "stop", service)
		d.exec.RunSudo(ctx, "systemctl", "disable", service)
	}

	log("Removing systemd unit files...")
	for _, unit := range []string{"broker.service", "controller.service", "kafka.service", "zookeeper.service", "broker-exporter.service", "controller-exporter.service", "kafka-exporter.service", "zookeeper-exporter.service"} {
		d.exec.RunSudo(ctx, "rm", "-f", filepath.Join("/etc/systemd/system", unit))
	}
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")

	log("Terminating processes on Kafka and ZooKeeper ports...")
	for _, port := range []string{"9092/tcp", "9093/tcp", "9095/tcp", "7071/tcp", "2181/tcp", "2888/tcp", "3888/tcp", "9308/tcp"} {
		d.exec.RunSudo(ctx, "fuser", "-k", port)
	}

	log("Rollback completed successfully. Configs and data are preserved.")
	return logs.String(), nil
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
	for _, service := range []string{"broker", "controller", "kafka", "zookeeper", "broker-exporter", "controller-exporter", "kafka-exporter", "zookeeper-exporter"} {
		d.exec.RunSudo(ctx, "systemctl", "stop", service)
		d.exec.RunSudo(ctx, "systemctl", "disable", service)
	}

	log("Removing systemd unit files...")
	for _, unit := range []string{"broker.service", "controller.service", "kafka.service", "zookeeper.service", "broker-exporter.service", "controller-exporter.service", "kafka-exporter.service", "zookeeper-exporter.service"} {
		d.exec.RunSudo(ctx, "rm", "-f", filepath.Join("/etc/systemd/system", unit))
	}
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	// 2. Kill remaining processes on ports
	log("Terminating processes on Kafka and ZooKeeper ports...")
	d.exec.RunSudo(ctx, "fuser", "-k", "9092/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9093/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9095/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "7071/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "2181/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "2888/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "3888/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9308/tcp")
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
	if strings.Contains(out, ":9092 ") || strings.Contains(out, ":9093 ") || strings.Contains(out, ":9095 ") || strings.Contains(out, ":7071 ") ||
		strings.Contains(out, ":2181 ") || strings.Contains(out, ":2888 ") || strings.Contains(out, ":3888 ") {
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
