package deploy

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/deploy/connect"
	"io.translab/tantor-agent/internal/deploy/kafka"
	"io.translab/tantor-agent/internal/deploy/ksqldb"
	"io.translab/tantor-agent/internal/deploy/monitoring"
	"io.translab/tantor-agent/internal/deploy/parcel"
	"io.translab/tantor-agent/internal/deploy/schema"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/pkg/api"
)

// Engine handles the deployment of services
type Engine struct {
	cfg    *config.Config
	client *client.APIClient
	exec   executor.Executor
}

func NewEngine(cfg *config.Config, client *client.APIClient, exec executor.Executor) *Engine {
	return &Engine{
		cfg:    cfg,
		client: client,
		exec:   exec,
	}
}

// Execute handles a task dispatched from the task poller
func (e *Engine) Execute(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	slog.Info("Executing deployment task", "taskId", t.TaskID, "command", t.Command)

	switch t.Command {
	case "INSTALL_KAFKA":
		return e.installKafka(ctx, t)
	case "UPGRADE_KAFKA":
		return e.upgradeKafka(ctx, t)
	case "INSTALL_CONNECT":
		return e.installConnect(ctx, t)
	case "INSTALL_SCHEMA":
		return e.installSchema(ctx, t)
	case "INSTALL_KSQLDB":
		return e.installKsql(ctx, t)
	case "INSTALL_MONITORING":
		return e.installMonitoring(ctx, t)
	case "DELETE_MONITORING":
		return e.deleteMonitoring(ctx, t)
	case "START_SERVICE":
		return e.startService(ctx, t)
	case "STOP_SERVICE":
		return e.stopService(ctx, t)
	case "RESTART_SERVICE":
		return e.restartService(ctx, t)
	case "UPDATE_KAFKA_CONFIG":
		return e.updateKafkaConfig(ctx, t)
	case "DELETE_CLUSTER":
		return e.deleteCluster(ctx, t)
	case "ROLLBACK_DEPLOYMENT":
		return e.rollbackDeployment(ctx, t)
	case "DISTRIBUTE_PARCEL":
		return e.distributeParcel(ctx, t)
	case "ACTIVATE_PARCEL":
		return e.activateParcel(ctx, t)
	case "DEACTIVATE_PARCEL":
		return e.deactivateParcel(ctx, t)
	case "REMOVE_PARCEL":
		return e.removeParcel(ctx, t)
	case "CHECK_PREREQUISITES":
		return e.checkPrerequisites(ctx, t)
	case "APPLY_PREREQUISITES":
		return e.applyPrerequisites(ctx, t)
	case "REBOOT_HOST":
		return e.scheduleHostReboot(ctx, t)
	case "CHECK_KRAFT_CONNECTIVITY":
		return e.checkKRaftConnectivity(ctx, t)
	case "VERIFY_KRAFT_QUORUM":
		return e.verifyKRaftQuorum(ctx, t)
	case "VERIFY_ZK_QUORUM":
		return e.verifyZooKeeperQuorum(ctx, t)
	default:
		return &api.TaskResult{
			TaskID:   t.TaskID,
			HostID:   e.cfg.Agent.HostID,
			Status:   "FAILED",
			ErrorMsg: fmt.Sprintf("Unknown command: %s", t.Command),
		}, nil
	}
}

func (e *Engine) upgradeKafka(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := kafka.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Upgrade(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Kafka upgrade failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logOutput}, nil
}

func (e *Engine) distributeParcel(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := parcel.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Distribute(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Parcel distribution failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logOutput}, nil
}

func (e *Engine) activateParcel(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := parcel.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Activate(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Parcel activation failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logOutput}, nil
}

func (e *Engine) deactivateParcel(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := parcel.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Deactivate(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Parcel deactivation failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logOutput}, nil
}

func (e *Engine) removeParcel(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := parcel.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Remove(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Parcel removal failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logOutput}, nil
}

func (e *Engine) deleteCluster(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := kafka.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Clean(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Cluster cleanup failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) rollbackDeployment(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := kafka.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Rollback(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Rollback failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) installKafka(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := kafka.NewDeployer(e.cfg, e.client, e.exec)
	currentStep := "Starting deployment"

	reporter := func(step string, logs string) {
		currentStep = step
		e.client.ReportTaskResult(&api.TaskResult{
			TaskID:      t.TaskID,
			HostID:      e.cfg.Agent.HostID,
			Status:      "IN_PROGRESS",
			CurrentStep: step,
			LogOutput:   logs,
		})
	}

	logOutput, err := deployer.Deploy(ctx, t, reporter)

	if err != nil {
		failResult := e.fail(t, fmt.Sprintf("Kafka deployment failed: %v\nLogs: %s", err, logOutput))
		failResult.CurrentStep = currentStep
		failResult.FailedReason = friendlyDeploymentFailure(currentStep, err)
		failResult.LogOutput = logOutput
		return failResult, nil
	}

	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func friendlyDeploymentFailure(step string, err error) string {
	detail := strings.ToLower(err.Error())
	switch {
	case strings.Contains(detail, "404"), strings.Contains(detail, "download"):
		return "Kafka could not be downloaded on the target host. Verify the artifact repository URL, artifact ID, and network access from the VM."
	case strings.Contains(detail, "checksum"):
		return "The downloaded Kafka package failed integrity verification. Upload or distribute the binary again before retrying."
	case strings.Contains(detail, "permission denied"), strings.Contains(detail, "not permitted"):
		return "The agent does not have permission to write files or manage the Kafka service. Verify its sudo and directory permissions."
	case strings.Contains(detail, "no space left"), strings.Contains(detail, "disk"):
		return "The target host does not have enough usable disk space for Kafka. Free space and retry the deployment."
	case strings.Contains(detail, "format"), strings.Contains(detail, "storage"):
		return "Kafka storage initialization failed. Verify the Kafka cluster ID, node ID, and data-directory permissions before retrying."
	case strings.Contains(detail, "systemctl"), strings.Contains(detail, "service"):
		return "Kafka was installed but its service could not start. Check the service configuration and the technical logs below."
	case strings.Contains(detail, "port"), strings.Contains(detail, "listening"):
		return "Kafka did not become reachable on its configured port. Check port conflicts, listeners, and firewall settings."
	case strings.Contains(detail, "config"), strings.Contains(detail, "properties"):
		return "Kafka configuration generation or validation failed. Review the selected properties and node topology."
	default:
		return fmt.Sprintf("Kafka deployment failed during '%s'. Review the recommended checks and technical logs below.", step)
	}
}

func (e *Engine) startService(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	serviceName := t.Parameters["service_name"]
	out, errOut, err := e.exec.RunSudo(ctx, "systemctl", "start", serviceName)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Failed to start service: %v, out: %s, errOut: %s", err, out, errOut)), nil
	}

	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: fmt.Sprintf("Service %s started successfully.", serviceName),
	}, nil
}

func (e *Engine) stopService(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	serviceName := t.Parameters["service_name"]
	out, errOut, err := e.exec.RunSudo(ctx, "systemctl", "stop", serviceName)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Failed to stop service: %v, out: %s, errOut: %s", err, out, errOut)), nil
	}

	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: fmt.Sprintf("Service %s stopped successfully.", serviceName),
	}, nil
}

func (e *Engine) restartService(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	serviceName := t.Parameters["service_name"]
	out, errOut, err := e.exec.RunSudo(ctx, "systemctl", "restart", serviceName)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Failed to restart service: %v, out: %s, errOut: %s", err, out, errOut)), nil
	}

	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: fmt.Sprintf("Service %s restarted successfully.", serviceName),
	}, nil
}

func (e *Engine) updateKafkaConfig(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := kafka.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.UpdateConfig(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Kafka config update failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) checkPrerequisites(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	var logs strings.Builder
	failed := 0

	logLine := func(status, name, detail string) {
		logs.WriteString(fmt.Sprintf("%s: %s [%s]\n", name, detail, status))
	}
	run := func(name string, command string, args ...string) bool {
		out, errOut, err := e.exec.Run(ctx, command, args...)
		detail := strings.TrimSpace(out)
		if detail == "" {
			detail = strings.TrimSpace(errOut)
		}
		if detail == "" {
			detail = command + " " + strings.Join(args, " ")
		}
		if err != nil {
			failed++
			logLine("Fail", name, detail)
			return false
		}
		logLine("Pass", name, detail)
		return true
	}

	logs.WriteString("\n===== Kafka System Pre-check =====\n")
	run("Open file limit (soft/hard)", "bash", "-c", "soft=$(ulimit -Sn); hard=$(ulimit -Hn); echo \"$soft/$hard\"; [[ \"$soft\" -ge 1024000 && \"$hard\" -ge 1024000 ]]")
	run("Swappiness", "bash", "-c", "value=$(cat /proc/sys/vm/swappiness 2>/dev/null); echo \"$value\"; [[ \"$value\" -eq 0 ]]")
	run("Transparent Huge Pages", "bash", "-c", "value=$(cat /sys/kernel/mm/transparent_hugepage/enabled 2>/dev/null); echo \"$value\"; [[ \"$value\" =~ \\[never\\] ]]")
	run("SELinux", "bash", "-c", "value=$(getenforce 2>/dev/null || echo Disabled); echo \"$value\"; [[ \"$value\" == Disabled || \"$value\" == Permissive ]]")
	run("Java Version", "bash", "-c", `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/jre/bin/java /usr/lib/jvm/default-java/bin/java /usr/java/latest/bin/java /usr/java/default/bin/java $JAVA_HOME/bin/java; do if command -v $p >/dev/null 2>&1; then JAVA_CMD=$p; break; fi; done; if [ -z "$JAVA_CMD" ]; then echo "Java not found"; exit 1; fi; output=$($JAVA_CMD -version 2>&1 | head -n 1); echo "$output"; echo "$output" | grep -qE '"?(11|17|21)\.'`)
	run("NTP Service", "bash", "-c", "if systemctl is-active --quiet ntpd; then echo 'ntpd Active'; elif systemctl is-active --quiet chronyd; then echo 'chronyd Active'; else echo 'Not running'; exit 1; fi")
	logs.WriteString("===== Pre-check Completed =====\n")
	if failed > 0 {
		msg := fmt.Sprintf("Prerequisite check failed: %d required checks failed", failed)
		logs.WriteString(msg + "\n")
		return e.fail(t, msg+"\n"+logs.String()), nil
	}
	logs.WriteString("Prerequisite check passed\n")
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logs.String()}, nil
}

func prerequisitePorts(raw string) []string {
	validPort := regexp.MustCompile(`^[0-9]{1,5}$`)
	seen := map[string]bool{}
	ports := make([]string, 0)
	for _, value := range strings.Split(raw, ",") {
		port := strings.TrimSpace(value)
		if validPort.MatchString(port) && !seen[port] {
			seen[port] = true
			ports = append(ports, port)
		}
	}
	if len(ports) == 0 {
		return []string{"9092", "9093", "7071"}
	}
	return ports
}

func (e *Engine) checkKRaftConnectivity(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	endpoints := splitEndpoints(t.Parameters["controller_endpoints"])
	if len(endpoints) == 0 {
		return e.fail(t, "controller_endpoints is required for KRaft connectivity validation"), nil
	}

	var logs strings.Builder
	for _, endpoint := range endpoints {
		connection, err := net.DialTimeout("tcp", endpoint, 5*time.Second)
		if err != nil {
			return e.fail(t, fmt.Sprintf("Controller endpoint %s is not reachable: %v\n%s", endpoint, err, logs.String())), nil
		}
		_ = connection.Close()
		logs.WriteString(fmt.Sprintf("[PASS] Controller endpoint %s is reachable\n", endpoint))
	}

	return &api.TaskResult{
		TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logs.String(),
	}, nil
}

func (e *Engine) verifyKRaftQuorum(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	endpoints := splitEndpoints(t.Parameters["controller_endpoints"])
	if len(endpoints) == 0 {
		return e.fail(t, "controller_endpoints is required for KRaft quorum verification"), nil
	}

	installBase := strings.TrimSpace(t.Parameters["kafka_install_dir"])
	if installBase == "" {
		installBase = "/opt"
	}
	activeDir := installBase
	if filepath.Base(filepath.Clean(activeDir)) != "kafka" {
		activeDir = filepath.Join(activeDir, "kafka")
	}
	quorumScript := filepath.Join(activeDir, "bin", "kafka-metadata-quorum.sh")
	envSetup := `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/jre/bin/java /usr/lib/jvm/default-java/bin/java /usr/java/latest/bin/java /usr/java/default/bin/java $JAVA_HOME/bin/java; do if command -v $p >/dev/null 2>&1; then JAVA_CMD=$p; break; fi; done; if [ -n "$JAVA_CMD" ]; then export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v $JAVA_CMD)))); export PATH=$JAVA_HOME/bin:$PATH; fi; `
	bashCmd := fmt.Sprintf("%s %s %s", envSetup, quorumScript, strings.Join([]string{"--bootstrap-controller", endpoints[0], "describe", "--status"}, " "))
	out, errOut, err := e.exec.Run(ctx, "bash", "-c", bashCmd)
	output := strings.TrimSpace(strings.TrimSpace(out) + "\n" + strings.TrimSpace(errOut))
	if err != nil {
		return e.fail(t, fmt.Sprintf("KRaft quorum status command failed: %v\n%s", err, output)), nil
	}

	expectedClusterID := strings.TrimSpace(t.Parameters["cluster_uuid"])
	if expectedClusterID == "" || !strings.Contains(output, expectedClusterID) {
		return e.fail(t, fmt.Sprintf("Quorum status did not report expected cluster ID %q\n%s", expectedClusterID, output)), nil
	}
	if !strings.Contains(output, "LeaderId:") {
		return e.fail(t, "Quorum status did not report a controller leader\n"+output), nil
	}
	if strings.Contains(output, "LeaderId: -1") {
		return e.fail(t, "KRaft quorum has no elected controller leader\n"+output), nil
	}
	expectedControllerCount, _ := strconv.Atoi(strings.TrimSpace(t.Parameters["expected_controller_count"]))
	if expectedControllerCount > 0 {
		currentVoterCount := 0
		for _, line := range strings.Split(output, "\n") {
			if strings.HasPrefix(strings.TrimSpace(line), "CurrentVoters:") {
				currentVoterCount = strings.Count(line, "\"id\"")
				break
			}
		}
		if currentVoterCount != expectedControllerCount {
			return e.fail(t, fmt.Sprintf(
				"KRaft quorum reports %d current voters; expected %d\n%s",
				currentVoterCount, expectedControllerCount, output,
			)), nil
		}
	}

	return &api.TaskResult{
		TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS",
		LogOutput: "[PASS] Runtime KRaft quorum is healthy\n" + output,
	}, nil
}

func (e *Engine) verifyZooKeeperQuorum(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	endpoints := splitEndpoints(t.Parameters["zookeeper_connect"])
	if len(endpoints) == 0 {
		return e.fail(t, "zookeeper_connect is required for ZooKeeper quorum verification"), nil
	}

	installBase := strings.TrimSpace(t.Parameters["kafka_install_dir"])
	if installBase == "" {
		installBase = "/opt"
	}
	activeDir := installBase
	if filepath.Base(filepath.Clean(activeDir)) != "kafka" {
		activeDir = filepath.Join(activeDir, "kafka")
	}
	zkShellScript := filepath.Join(activeDir, "bin", "zookeeper-shell.sh")

	var logs strings.Builder
	for _, endpoint := range endpoints {
		envSetup := `source /etc/profile 2>/dev/null; source ~/.bash_profile 2>/dev/null; source ~/.bashrc 2>/dev/null; JAVA_CMD=""; for p in java /usr/bin/java /usr/lib/jvm/jre/bin/java /usr/lib/jvm/default-java/bin/java /usr/java/latest/bin/java /usr/java/default/bin/java $JAVA_HOME/bin/java; do if command -v $p >/dev/null 2>&1; then JAVA_CMD=$p; break; fi; done; if [ -n "$JAVA_CMD" ]; then export JAVA_HOME=$(dirname $(dirname $(readlink -f $(command -v $JAVA_CMD)))); export PATH=$JAVA_HOME/bin:$PATH; fi; `
		bashCmd := fmt.Sprintf("%s %s %s", envSetup, zkShellScript, strings.Join([]string{endpoint, "ls", "/"}, " "))
		out, errOut, err := e.exec.Run(ctx, "bash", "-c", bashCmd)
		output := strings.TrimSpace(strings.TrimSpace(out) + "\n" + strings.TrimSpace(errOut))
		if err != nil {
			return e.fail(t, fmt.Sprintf("ZooKeeper quorum status command failed for endpoint %s: %v\n%s", endpoint, err, output)), nil
		}
		if !strings.Contains(output, "[") {
			return e.fail(t, fmt.Sprintf("ZooKeeper quorum status did not report a valid response for endpoint %s\n%s", endpoint, output)), nil
		}
		logs.WriteString(fmt.Sprintf("[PASS] ZooKeeper quorum is healthy via endpoint %s\n", endpoint))
	}

	return &api.TaskResult{
		TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS",
		LogOutput: logs.String(),
	}, nil
}

func splitEndpoints(raw string) []string {
	seen := make(map[string]bool)
	endpoints := make([]string, 0)
	for _, value := range strings.Split(raw, ",") {
		endpoint := strings.TrimSpace(value)
		if at := strings.LastIndex(endpoint, "@"); at >= 0 {
			endpoint = strings.TrimSpace(endpoint[at+1:])
		}
		if endpoint == "" || seen[endpoint] {
			continue
		}
		seen[endpoint] = true
		endpoints = append(endpoints, endpoint)
	}
	return endpoints
}

func (e *Engine) fail(t *api.Task, msg string) *api.TaskResult {
	slog.Error("Task failed", "taskId", t.TaskID, "error", msg)
	return &api.TaskResult{
		TaskID:   t.TaskID,
		HostID:   e.cfg.Agent.HostID,
		Status:   "FAILED",
		ErrorMsg: msg,
	}
}

func (e *Engine) installConnect(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := connect.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Deploy(ctx, t)

	if err != nil {
		return e.fail(t, fmt.Sprintf("Connect deployment failed: %v\nLogs: %s", err, logOutput)), nil
	}

	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) installSchema(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := schema.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Deploy(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Schema Registry deployment failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) installKsql(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := ksqldb.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Deploy(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("ksqlDB deployment failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) installMonitoring(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := monitoring.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Deploy(ctx, t)
	if err != nil {
		return e.fail(t, fmt.Sprintf("Monitoring deployment failed: %v\nLogs: %s", err, logOutput)), nil
	}
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
}

func (e *Engine) deleteMonitoring(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	installDir := t.Parameters["install_dir"]
	if installDir == "" {
		installDir = "/opt/tantor/monitoring"
	}
	if _, errOut, err := e.exec.RunSudo(ctx, "systemctl", "disable", "--now", "prometheus", "grafana"); err != nil {
		return e.fail(t, fmt.Sprintf("Failed to stop monitoring services: %v: %s", err, errOut)), nil
	}
	e.exec.RunSudo(ctx, "rm", "-f", "/etc/systemd/system/prometheus.service", "/etc/systemd/system/grafana.service")
	if _, errOut, err := e.exec.RunSudo(ctx, "rm", "-rf", installDir); err != nil {
		return e.fail(t, fmt.Sprintf("Failed to remove monitoring directory: %v: %s", err, errOut)), nil
	}
	e.exec.RunSudo(ctx, "systemctl", "daemon-reload")
	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: "Monitoring services stopped and files removed.",
	}, nil
}
