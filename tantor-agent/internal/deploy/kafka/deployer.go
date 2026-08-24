package kafka

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
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
	os.MkdirAll(artifactWorkDir, 0755)
	for _, dir := range []string{paths.LogDirs, paths.MetadataLogDir, paths.AppLogDir} {
		if dir != "" {
			d.exec.RunSudo(ctx, "mkdir", "-p", strings.TrimSpace(dir))
		}
	}

	setStep("Validate package")
	if t.ArtifactURL == "" {
		log("Warning: Artifact URL is empty, skipping download.")
	} else {
		log("Artifact URL is valid: %s", t.ArtifactURL)
	}

	setStep("Download package to agent")
	destPath := filepath.Join(artifactWorkDir, fmt.Sprintf("kafka_%s.tgz", t.TaskID))
	var downloadedChecksum string
	var err error

	if !shouldSkip("Download package to agent") {
		log("Downloading artifact from %s to %s", t.ArtifactURL, destPath)
		downloadedChecksum, err = d.client.DownloadArtifact(t.ArtifactURL, destPath)
		if err != nil {
			return logs.String(), fmt.Errorf("failed to download artifact: %w", err)
		}
	} else {
		log("Skipping step (resume mode)")
	}

	setStep("Verify checksum")
	if !shouldSkip("Verify checksum") {
		expectedChecksum := t.Checksum
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
		if _, _, installedErr := d.exec.RunSudo(ctx, "test", "-x", installedLauncher); installedErr == nil {
			log("Kafka %s is already extracted at %s; preserving existing role configurations", t.Parameters["version"], installDir)
		} else {
			_, _, err = d.exec.RunSudo(ctx, "tar", "-xzf", destPath, "-C", installDir, "--strip-components=1")
			if err != nil {
				return logs.String(), fmt.Errorf("failed to extract tar: %w", err)
			}
			log("Artifact extracted to %s", installDir)
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
	d.exec.RunSudo(ctx, "mkdir", "-p", jmxDir)
	d.exec.RunSudo(ctx, "chmod", "755", jmxDir)
	jmxJarPath := filepath.Join(jmxDir, "jmx_prometheus_javaagent.jar")

	log("Downloading JMX Exporter to %s", jmxJarPath)

	jmxUrl := t.Parameters["jmx_artifact_url"]
	jmxInstalled := false
	if jmxUrl != "" {
		log("Using JMX Artifact URL from Tantor Server: %s", jmxUrl)
		tmpJmx := filepath.Join(artifactWorkDir, "jmx_tmp.jar")
		_, err = d.client.DownloadArtifact(jmxUrl, tmpJmx)
		if err != nil {
			log("Warning: Failed to download JMX agent from artifact repo: %v", err)
			os.Remove(tmpJmx)
		} else if !isUsableJar(tmpJmx) {
			log("Warning: Downloaded JMX artifact is not a valid jar; keeping existing jar if present")
			os.Remove(tmpJmx)
		} else {
			d.exec.RunSudo(ctx, "mv", tmpJmx, jmxJarPath)
			d.exec.RunSudo(ctx, "chmod", "644", jmxJarPath)
			jmxInstalled = true
		}
	}

	if !jmxInstalled {
		if isUsableJar(jmxJarPath) {
			log("Using existing valid JMX exporter jar at %s", jmxJarPath)
			jmxInstalled = true
		} else {
			log("Falling back to Maven Central for JMX exporter...")
		}
	}

	if !jmxInstalled {
		resp, err := http.Get("https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.20.0/jmx_prometheus_javaagent-0.20.0.jar")
		if err == nil && resp.StatusCode == http.StatusOK {
			defer resp.Body.Close()
			tmpJmx := filepath.Join(artifactWorkDir, "jmx_tmp.jar")
			out, _ := os.Create(tmpJmx)
			io.Copy(out, resp.Body)
			out.Close()
			if isUsableJar(tmpJmx) {
				d.exec.RunSudo(ctx, "mv", tmpJmx, jmxJarPath)
				d.exec.RunSudo(ctx, "chmod", "644", jmxJarPath)
				jmxInstalled = true
			} else {
				log("Warning: Maven Central JMX download did not produce a valid jar")
				os.Remove(tmpJmx)
			}
		} else {
			if err != nil {
				log("Warning: Failed to download JMX agent from Maven Central: %v", err)
			} else {
				log("Warning: Failed to download JMX agent from Maven Central: status %d", resp.StatusCode)
				resp.Body.Close()
			}
		}
	}

	if !jmxInstalled {
		return logs.String(), fmt.Errorf("JMX exporter jar is unavailable. Upload a JMX_EXPORTER artifact to Tantor and retry deployment")
	}

	jmxConfigPath := jmxConfigPathForRole(installDir, t.Parameters["role"])
	if err := d.writeTemplateToSudoFile(ctx, JmxConfigTemplate, nil, jmxConfigPath); err != nil {
		return logs.String(), fmt.Errorf("failed to write JMX exporter config: %w", err)
	}
	log("JMX exporter config written for role %s at %s", t.Parameters["role"], jmxConfigPath)

	// Kafka Exporter is a broker-level service. Controller-only nodes do not
	// expose broker metadata, so installing an exporter there would be both
	// misleading and unable to provide useful samples.
	kafkaExporterInstalled, err := d.installKafkaExporter(ctx, t, installDir, artifactWorkDir, log)
	if err != nil {
		return logs.String(), err
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
			metaPropsDirs := metaPropertiesDirsForRole(paths)
			clusterUUID := strings.TrimSpace(t.Parameters["cluster_uuid"])
			nodeID := strings.TrimSpace(t.Parameters["node_id"])
			if clusterUUID == "" || nodeID == "" {
				return logs.String(), fmt.Errorf("cluster_uuid and node_id are required before formatting KRaft storage")
			}
			if _, err := os.Stat(filepath.Join(paths.MetaPropertiesDir, "meta.properties")); os.IsNotExist(err) {
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
				envSetup := `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/jre/bin/java /usr/lib/jvm/default-java/bin/java /usr/java/latest/bin/java /usr/java/default/bin/java $JAVA_HOME/bin/java; do if command -v $p >/dev/null 2>&1; then JAVA_CMD=$p; break; fi; done; if [ -n "$JAVA_CMD" ]; then export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v $JAVA_CMD)))); export PATH=$JAVA_HOME/bin:$PATH; fi; `
				bashCmd := fmt.Sprintf("%s %s %s", envSetup, storageScript, strings.Join(formatArgs, " "))
				formatOut, formatErr, err := d.exec.Run(ctx, "bash", "-c", bashCmd)
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
				if err := validateMetaProperties(ctx, d, metaPropsDirs, clusterUUID, nodeID); err != nil {
					return logs.String(), fmt.Errorf("formatted storage identity validation failed: %w", err)
				}
				log("KRaft storage formatted successfully")
			} else if err != nil {
				return logs.String(), fmt.Errorf("failed to inspect KRaft metadata: %w", err)
			} else {
				if err := validateMetaProperties(ctx, d, metaPropsDirs, clusterUUID, nodeID); err != nil {
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
	if kafkaExporterInstalled {
		if err := d.createKafkaExporterSystemdService(ctx, activeInstallDir, t); err != nil {
			return logs.String(), fmt.Errorf("failed to create Kafka Exporter service: %w", err)
		}
	}
	log("Systemd service created")

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
		}
		if err != nil {
			return logs.String(), fmt.Errorf("failed to start service: %w", err)
		}
		log("Kafka service %s started successfully", serviceName)
		if kafkaExporterInstalled {
			if _, errOut, exporterErr := d.exec.RunSudo(ctx, "systemctl", "enable", "--now", "kafka-exporter"); exporterErr != nil {
				return logs.String(), fmt.Errorf("failed to start Kafka Exporter: %w (%s)", exporterErr, strings.TrimSpace(errOut))
			}
			if exporterErr := d.waitForKafkaExporter(ctx, kafkaExporterPort(t)); exporterErr != nil {
				journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", "kafka-exporter", "-n", "50", "--no-pager")
				return logs.String(), fmt.Errorf("Kafka Exporter did not become ready: %w\n%s", exporterErr, journalOut)
			}
			log("Kafka Exporter started successfully on port %s", kafkaExporterPort(t))
		}
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
		envSetup := `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/jre/bin/java /usr/lib/jvm/default-java/bin/java /usr/java/latest/bin/java /usr/java/default/bin/java $JAVA_HOME/bin/java; do if command -v $p >/dev/null 2>&1; then JAVA_CMD=$p; break; fi; done; if [ -n "$JAVA_CMD" ]; then export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v $JAVA_CMD)))); export PATH=$JAVA_HOME/bin:$PATH; fi; `
		bashCmd := fmt.Sprintf("%s %s %s", envSetup, topicScript, strings.Join([]string{"--list", "--bootstrap-server", "localhost:" + listenerPort}, " "))
		out, errOut, err := d.exec.Run(ctx, "bash", "-c", bashCmd)
		if err != nil {
			log("Warning: AdminClient validation failed (non-fatal): %v, out: %s, errOut: %s", err, out, errOut)
		} else {
			log("AdminClient successfully connected to broker")
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
	role, isBroker, isController := normalizeKRaftRole(t.Parameters["role"])
	jmxMetricsPort := t.Parameters["jmx_port"]
	if jmxMetricsPort == "" {
		jmxMetricsPort = defaultJmxMetricsPort(t.Parameters["role"])
	}
	jmxRequired := jmxRequiredForTask(t, isBroker, isController)

	// Report VALIDATING status
	if err := d.client.ReportTaskResult(&api.TaskResult{
		TaskID:     t.TaskID,
		ClaimToken: t.ClaimToken,
		HostID:     d.cfg.Agent.HostID,
		Status:     "VALIDATING",
	}); err != nil {
		log("Warning: Failed to report VALIDATING status: %v", err)
	}

	log("Validation [1/6]: Checking Kafka process...")
	for i := 0; i < 10; i++ {
		out, _, _ := d.exec.Run(ctx, "bash", "-c", "ps -eo pid,cmd | grep java | grep -E 'kafka\\.Kafka|kafka\\.server\\.KafkaRaftServer' | grep -v grep | awk '{print $1}'")
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
	if _, err := os.Stat(filepath.Join(paths.MetaPropertiesDir, "meta.properties")); err != nil {
		return fmt.Errorf("KRaft meta.properties not found in %s", paths.MetaPropertiesDir)
	}
	log("  ✓ KRaft meta.properties exists")

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
	if strings.Contains(out2, "jmx_prometheus_javaagent") && strings.Contains(out2, "="+jmxMetricsPort+":") {
		log("  JMX Prometheus Exporter attached")
	} else {
		if jmxRequired {
			journalOut, _, _ := d.exec.RunSudo(ctx, "journalctl", "-u", serviceNameForTask(t), "-n", "80", "--no-pager")
			return fmt.Errorf("JMX exporter not attached to %s process on port %s. Logs:\n%s", role, jmxMetricsPort, journalOut)
		}
		log("  JMX Exporter is disabled for role %s", role)
		return nil
	}

	log("Validation [6/6]: Checking metrics endpoint on port %s...", jmxMetricsPort)
	for i := 0; i < 5; i++ {
		_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("curl -sf http://localhost:%s/metrics | head -1", jmxMetricsPort))
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
	log("  Metrics endpoint is disabled for role %s", role)

	return nil
}

func (d *Deployer) validateZooKeeperDeployment(ctx context.Context, t *api.Task, logs *strings.Builder) error {
	log := func(msg string, args ...interface{}) {
		logs.WriteString(fmt.Sprintf(msg, args...) + "\n")
	}

	if err := d.client.ReportTaskResult(&api.TaskResult{
		TaskID:     t.TaskID,
		ClaimToken: t.ClaimToken,
		HostID:     d.cfg.Agent.HostID,
		Status:     "VALIDATING",
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
		out, _, _ := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("ps -eo pid,cmd | grep java | grep '%s' | grep -v grep | awk '{print $1}'", processPattern))
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
	if err := d.waitForListeningPort(ctx, port); err != nil {
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

func (d *Deployer) waitForListeningPort(ctx context.Context, port string) error {
	for i := 0; i < 10; i++ {
		_, _, err := d.exec.Run(ctx, "bash", "-c", fmt.Sprintf("ss -tlnp | grep -E ':%s\\b'", port))
		if err == nil {
			return nil
		}
		time.Sleep(3 * time.Second)
	}
	return fmt.Errorf("port %s not listening after 30s", port)
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

func kafkaArtifactWorkDir(t *api.Task, fallback string) string {
	configured := strings.TrimSpace(t.Parameters["artifact_load_dir"])
	if configured == "" {
		configured = strings.TrimSpace(t.Parameters["artifacts_dir"])
	}
	if configured == "" {
		return fallback
	}
	return filepath.Clean(configured)
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
	hostname := getLocalIP()
	quorumMode := strings.ToLower(strings.TrimSpace(t.Parameters["kraft_quorum_mode"]))
	if quorumMode == "" {
		quorumMode = "static"
	}
	if quorumMode != "static" && quorumMode != "dynamic" {
		return fmt.Errorf("unsupported KRaft quorum mode %q", quorumMode)
	}
	quorumVoters := t.Parameters["quorum_voters"]
	quorumBootstrap := strings.TrimSpace(t.Parameters["quorum_bootstrap_servers"])
	if quorumMode == "static" && quorumVoters == "" {
		quorumVoters = fmt.Sprintf("%s@%s:9093", nodeId, hostname)
	}
	if quorumBootstrap == "" {
		quorumBootstrap = quorumBootstrapServers(quorumVoters)
	}
	if quorumMode == "dynamic" && quorumBootstrap == "" {
		return fmt.Errorf("quorum_bootstrap_servers is required for dynamic KRaft quorum")
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
		if err := d.ensureDirectory(ctx, zkDataDir); err != nil {
			return fmt.Errorf("failed to create ZooKeeper data directory: %w", err)
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
	hostname := getLocalIP()

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

func validateMetaProperties(ctx context.Context, d *Deployer, dirs []string, expectedClusterID, expectedNodeID string) error {
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
		if values["node.id"] != "" && values["node.id"] != expectedNodeID {
			return fmt.Errorf("Invalid node.id in: %s. Expected new node ID %q, but read old node ID %q.", metaPropsPath, expectedNodeID, values["node.id"])
		}
	}
	return nil
}

// metaPropertiesDirsForRole limits storage identity validation to the
// directories owned by the role being deployed. A broker and a controller can
// legitimately use different node IDs on the same host, so validating the
// other role's meta.properties would produce a false identity mismatch.
func metaPropertiesDirsForRole(paths kafkaRolePaths) []string {
	return []string{paths.MetadataLogDir, paths.LogDirs}
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
	envSetup := `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/jre/bin/java /usr/lib/jvm/default-java/bin/java /usr/java/latest/bin/java /usr/java/default/bin/java $JAVA_HOME/bin/java; do if command -v $p >/dev/null 2>&1; then JAVA_CMD=$p; break; fi; done; if [ -n "$JAVA_CMD" ]; then export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v $JAVA_CMD)))); export PATH=$JAVA_HOME/bin:$PATH; fi; `
	bashCmd := fmt.Sprintf("%s %s %s", envSetup, versionScript, "--version")
	out, errOut, err := d.exec.Run(ctx, "bash", "-c", bashCmd)
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

func defaultJmxMetricsPort(role string) string {
	_, isBroker, isController := normalizeKRaftRole(role)
	if isController && !isBroker {
		return "7072"
	}
	return "7071"
}

func jmxConfigPathForRole(installDir, role string) string {
	_, isBroker, isController := normalizeKRaftRole(role)
	name := "broker.yml"
	if isController && !isBroker {
		name = "controller.yml"
	} else if isBroker && isController {
		name = "broker-controller.yml"
	}
	return filepath.Join(installDir, "jmx", name)
}

func jmxRequiredForTask(t *api.Task, isBroker, isController bool) bool {
	if strings.EqualFold(strings.TrimSpace(t.Parameters["jmx_enabled"]), "false") {
		return false
	}
	if strings.EqualFold(strings.TrimSpace(t.Parameters["jmx_required"]), "true") {
		return true
	}
	return isBroker || isController
}

func kafkaExporterEnabledForTask(t *api.Task) bool {
	if strings.EqualFold(strings.TrimSpace(t.Parameters["kafka_exporter_enabled"]), "false") {
		return false
	}
	role := firstNonEmpty(t.Parameters["service_role"], t.Parameters["role"])
	_, isBroker, _ := normalizeKRaftRole(role)
	return isBroker
}

func kafkaExporterPort(t *api.Task) string {
	port := firstNonEmpty(t.Parameters["kafka_exporter_port"], t.Parameters["exporter_port"], "9308")
	parsed, err := strconv.Atoi(port)
	if err != nil || parsed < 1 || parsed > 65535 {
		return "9308"
	}
	return port
}

func kafkaBrokerPort(t *api.Task) string {
	return firstNonEmpty(t.Parameters["listener_port"], t.Parameters["broker_port"], "9092")
}

func kafkaExporterBrokerHost(t *api.Task) string {
	return firstNonEmpty(t.Parameters["host_ip"], t.Parameters["advertised_host"], getLocalIP())
}

func (d *Deployer) installKafkaExporter(
	ctx context.Context,
	t *api.Task,
	installDir string,
	artifactWorkDir string,
	log func(string, ...interface{}),
) (bool, error) {
	if !kafkaExporterEnabledForTask(t) {
		log("Kafka Exporter is not applicable to role %s", firstNonEmpty(t.Parameters["service_role"], t.Parameters["role"], "unknown"))
		return false, nil
	}

	finalBinary := filepath.Join(installDir, "bin", "kafka_exporter")
	artifactURL := firstNonEmpty(
		t.Parameters["kafka_exporter_download_url"],
		t.Parameters["kafka_exporter_artifact_url"],
		t.Parameters["kafkaExporterArtifactUrl"],
	)
	artifactID := firstNonEmpty(t.Parameters["kafka_exporter_artifact_id"], t.Parameters["kafkaExporterArtifactId"])
	if artifactURL == "" && artifactID != "" {
		artifactURL = strings.TrimRight(d.cfg.Agent.ServerURL, "/") + "/api/v1/artifacts/" + url.PathEscape(artifactID) + "/download"
	}

	if artifactURL == "" {
		if _, _, err := d.exec.RunSudo(ctx, "test", "-x", finalBinary); err == nil {
			log("Using existing Kafka Exporter binary at %s", finalBinary)
			return true, nil
		}
		log("Kafka Exporter artifact was not supplied; broker deployment will continue without Kafka Exporter")
		return false, nil
	}

	archivePath := filepath.Join(artifactWorkDir, fmt.Sprintf("kafka_exporter_%s.tgz", t.TaskID))
	downloadedChecksum, err := d.client.DownloadArtifact(artifactURL, archivePath)
	if err != nil {
		return false, fmt.Errorf("failed to download Kafka Exporter artifact: %w", err)
	}
	defer os.Remove(archivePath)

	expectedChecksum := firstNonEmpty(
		t.Parameters["kafka_exporter_checksum"],
		t.Parameters["kafka_exporter_sha256"],
		t.Parameters["kafkaExporterChecksum"],
		downloadedChecksum,
	)
	if expectedChecksum != "" {
		if err := checksum.VerifySHA256(archivePath, expectedChecksum); err != nil {
			return false, fmt.Errorf("Kafka Exporter checksum verification failed: %w", err)
		}
	}

	tmpBinary := filepath.Join(artifactWorkDir, fmt.Sprintf("kafka_exporter_%s.bin", t.TaskID))
	defer os.Remove(tmpBinary)
	if err := extractKafkaExporterBinary(archivePath, tmpBinary); err != nil {
		return false, fmt.Errorf("failed to extract Kafka Exporter artifact: %w", err)
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", filepath.Dir(finalBinary)); err != nil {
		return false, fmt.Errorf("failed to create Kafka Exporter binary directory: %w (%s)", err, strings.TrimSpace(errOut))
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "mv", tmpBinary, finalBinary); err != nil {
		return false, fmt.Errorf("failed to install Kafka Exporter binary: %w (%s)", err, strings.TrimSpace(errOut))
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "chmod", "755", finalBinary); err != nil {
		return false, fmt.Errorf("failed to make Kafka Exporter executable: %w (%s)", err, strings.TrimSpace(errOut))
	}
	log("Kafka Exporter binary installed at %s", finalBinary)
	return true, nil
}

func extractKafkaExporterBinary(archivePath, destination string) error {
	archive, err := os.Open(archivePath)
	if err != nil {
		return err
	}
	defer archive.Close()

	gzipReader, err := gzip.NewReader(archive)
	if err != nil {
		return fmt.Errorf("open gzip stream: %w", err)
	}
	defer gzipReader.Close()

	tarReader := tar.NewReader(gzipReader)
	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return fmt.Errorf("read tar stream: %w", err)
		}
		if header.Typeflag != tar.TypeReg || filepath.Base(filepath.Clean(header.Name)) != "kafka_exporter" {
			continue
		}
		if header.Size <= 0 || header.Size > 256*1024*1024 {
			return fmt.Errorf("invalid Kafka Exporter binary size %d", header.Size)
		}
		out, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0755)
		if err != nil {
			return err
		}
		_, copyErr := io.CopyN(out, tarReader, header.Size)
		closeErr := out.Close()
		if copyErr != nil {
			return fmt.Errorf("extract Kafka Exporter binary: %w", copyErr)
		}
		if closeErr != nil {
			return closeErr
		}
		return nil
	}
	return fmt.Errorf("archive does not contain a kafka_exporter binary")
}

func (d *Deployer) createKafkaExporterSystemdService(ctx context.Context, installDir string, t *api.Task) error {
	props := struct {
		InstallDir   string
		ExporterPort string
		KafkaHost    string
		KafkaPort    string
	}{
		InstallDir:   installDir,
		ExporterPort: kafkaExporterPort(t),
		KafkaHost:    kafkaExporterBrokerHost(t),
		KafkaPort:    kafkaBrokerPort(t),
	}
	return d.writeTemplateToSudoFile(ctx, KafkaExporterSystemdTemplate, props, "/etc/systemd/system/kafka-exporter.service")
}

func (d *Deployer) waitForKafkaExporter(ctx context.Context, port string) error {
	deadline := time.Now().Add(30 * time.Second)
	for time.Now().Before(deadline) {
		connection, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", port), time.Second)
		if err == nil {
			connection.Close()
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Second):
		}
	}
	return fmt.Errorf("port %s did not start listening within 30 seconds", port)
}

func (d *Deployer) createSystemdService(ctx context.Context, user, installDir string, t *api.Task) error {
	// Find Java Home
	javaHome := strings.TrimSpace(t.Parameters["java_home"])
	if javaHome == "" {
		findJavaCmd := `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/*/bin/java /usr/java/*/bin/java /opt/*/bin/java; do if [ -x "$p" ]; then JAVA_CMD="$p"; break; fi; if command -v "$p" >/dev/null 2>&1; then JAVA_CMD=$(command -v "$p"); break; fi; done; if [ -n "$JAVA_CMD" ]; then dirname "$(dirname "$(readlink -f "$JAVA_CMD")")"; fi`
		out, _, _ := d.exec.Run(ctx, "bash", "-c", findJavaCmd)
		javaHome = strings.TrimSpace(out)
	}
	if javaHome == "" || javaHome == "." {
		javaHome = "/usr" // fallback
	}

	heapSize := t.Parameters["heap_size"]
	if heapSize == "" {
		heapSize = "1G"
	}

	jmxPort := t.Parameters["jmx_port"]
	if jmxPort == "" {
		jmxPort = defaultJmxMetricsPort(t.Parameters["role"])
	}
	dataDir := t.Parameters["kafka_data_dir"]
	if dataDir == "" {
		installPaths := resolveKafkaInstallPaths(t)
		dataDir = defaultKafkaDataDir(installPaths.BaseDir)
	}
	paths := resolveKafkaRolePaths(t, installDir, dataDir)

	serviceName := serviceNameForTask(t)
	jmxAgentPath := filepath.Join(installDir, "jmx", "jmx_prometheus_javaagent.jar")
	jmxConfigPath := jmxConfigPathForRole(installDir, t.Parameters["role"])
	_, isBroker, isController := normalizeKRaftRole(t.Parameters["role"])
	if !jmxRequiredForTask(t, isBroker, isController) {
		jmxPort = ""
		jmxAgentPath = ""
		jmxConfigPath = ""
	} else if !isUsableJar(jmxAgentPath) {
		if isBroker || isController {
			return fmt.Errorf("JMX exporter jar is missing or invalid at %s", jmxAgentPath)
		}
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
	return d.writeTemplateToSudoFile(ctx, serviceTemplate, props, filepath.Join("/etc/systemd/system", serviceName+".service"))
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

func (d *Deployer) ensureDirectory(ctx context.Context, dir string) error {
	if d.exec == nil {
		return os.MkdirAll(dir, 0755)
	}
	if _, errOut, err := d.exec.RunSudo(ctx, "mkdir", "-p", dir); err != nil {
		return fmt.Errorf("%w (%s)", err, errOut)
	}
	return nil
}

func (d *Deployer) writeStringToSudoFile(ctx context.Context, content string, dest string) error {
	if d.exec == nil {
		if err := d.ensureDirectory(ctx, filepath.Dir(dest)); err != nil {
			return fmt.Errorf("failed to create dir %s: %w", filepath.Dir(dest), err)
		}
		content = strings.ReplaceAll(content, "\r\n", "\n")
		if !strings.HasSuffix(content, "\n") {
			content += "\n"
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
	for _, service := range []string{"broker", "controller", "kafka", "zookeeper", "kafka-exporter"} {
		d.exec.RunSudo(ctx, "systemctl", "stop", service)
		d.exec.RunSudo(ctx, "systemctl", "disable", service)
	}

	log("Removing systemd unit files...")
	for _, unit := range []string{"broker.service", "controller.service", "kafka.service", "zookeeper.service", "kafka-exporter.service"} {
		d.exec.RunSudo(ctx, "rm", "-f", filepath.Join("/etc/systemd/system", unit))
	}
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")

	log("Terminating processes on Kafka and ZooKeeper ports...")
	for _, port := range []string{"9092/tcp", "9093/tcp", "9095/tcp", "7071/tcp", "7072/tcp", "9308/tcp", "2181/tcp", "2888/tcp", "3888/tcp"} {
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
	for _, service := range []string{"broker", "controller", "kafka", "zookeeper", "kafka-exporter"} {
		d.exec.RunSudo(ctx, "systemctl", "stop", service)
		d.exec.RunSudo(ctx, "systemctl", "disable", service)
	}

	log("Removing systemd unit files...")
	for _, unit := range []string{"broker.service", "controller.service", "kafka.service", "zookeeper.service", "kafka-exporter.service"} {
		d.exec.RunSudo(ctx, "rm", "-f", filepath.Join("/etc/systemd/system", unit))
	}
	d.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	// 2. Kill remaining processes on ports
	log("Terminating processes on Kafka and ZooKeeper ports...")
	d.exec.RunSudo(ctx, "fuser", "-k", "9092/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9093/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9095/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "7071/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "7072/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "9308/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "2181/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "2888/tcp")
	d.exec.RunSudo(ctx, "fuser", "-k", "3888/tcp")
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
	if strings.Contains(out, ":9092 ") || strings.Contains(out, ":9093 ") || strings.Contains(out, ":9095 ") || strings.Contains(out, ":7071 ") || strings.Contains(out, ":7072 ") || strings.Contains(out, ":9308 ") ||
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
