package deploy

import (
	"context"
	"fmt"
	"log/slog"
	"strings"

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

func (e *Engine) installKafka(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	deployer := kafka.NewDeployer(e.cfg, e.client, e.exec)
	logOutput, err := deployer.Deploy(ctx, t)

	if err != nil {
		return e.fail(t, fmt.Sprintf("Kafka deployment failed: %v\nLogs: %s", err, logOutput)), nil
	}

	return &api.TaskResult{
		TaskID:    t.TaskID,
		HostID:    e.cfg.Agent.HostID,
		Status:    "SUCCESS",
		LogOutput: logOutput,
	}, nil
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
	warned := 0

	logLine := func(status, name, detail string) {
		logs.WriteString(fmt.Sprintf("[%s] %s - %s\n", status, name, detail))
	}
	run := func(name string, required bool, command string, args ...string) {
		out, errOut, err := e.exec.Run(ctx, command, args...)
		detail := strings.TrimSpace(out)
		if detail == "" {
			detail = strings.TrimSpace(errOut)
		}
		if detail == "" {
			detail = command + " " + strings.Join(args, " ")
		}
		if err != nil {
			if required {
				failed++
				logLine("FAIL", name, fmt.Sprintf("%v: %s", err, detail))
			} else {
				warned++
				logLine("WARN", name, fmt.Sprintf("%v: %s", err, detail))
			}
			return
		}
		logLine("PASS", name, detail)
	}

	logs.WriteString("Tantor host prerequisite check\n")
	logs.WriteString("================================\n")
	run("Agent user", false, "id", "-un")
	run("OS release", false, "bash", "-lc", "cat /etc/os-release | head -5")
	run("Java runtime", true, "bash", "-lc", "java -version 2>&1 | head -1")
	run("Systemd available", true, "bash", "-lc", "command -v systemctl && systemctl --version | head -1")
	run("Bash available", true, "bash", "-lc", "command -v bash")
	run("Tar available", true, "bash", "-lc", "command -v tar")
	run("Primary routable IP", true, "bash", "-lc", "dev=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}'); ipaddr=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"src\"){print $(i+1); exit}}'); test -n \"$ipaddr\" || { echo no primary source IP found; exit 1; }; case \"$ipaddr\" in 127.*|169.254.*|172.17.*|172.18.*) echo unusable primary IP $ipaddr on $dev; exit 1;; esac; echo primary IP $ipaddr on $dev")
	run("Static IP method", false, "bash", "-lc", "dev=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i==\"dev\"){print $(i+1); exit}}'); if ! command -v nmcli >/dev/null 2>&1; then echo nmcli not installed; exit 1; fi; con=$(nmcli -t -f NAME,DEVICE con show --active | awk -F: -v d=\"$dev\" '$2==d{print $1; exit}'); test -n \"$con\" || { echo no active NetworkManager connection for $dev; exit 1; }; method=$(nmcli -g ipv4.method con show \"$con\" | head -1); if [ \"$method\" = manual ]; then echo $dev uses static/manual IPv4 via $con; else echo $dev uses IPv4 method $method, confirm DHCP reservation/static IP before Kafka deploy; exit 1; fi")
	run("Kernel vm.max_map_count", false, "bash", "-lc", "value=$(sysctl -n vm.max_map_count 2>/dev/null); test -n \"$value\" || { echo vm.max_map_count unavailable; exit 1; }; if [ \"$value\" -lt 262144 ]; then echo vm.max_map_count=$value, recommended >=262144 for large Kafka deployments; exit 1; else echo vm.max_map_count=$value; fi")
	run("Open file limit", false, "bash", "-lc", "limit=$(ulimit -n); if [ \"$limit\" -lt 100000 ]; then echo nofile=$limit, recommended >=100000; exit 1; else echo nofile=$limit; fi")
	run("Disk space", true, "bash", "-lc", "df -h / /opt 2>/dev/null | tail -n +1")
	run("Memory", true, "bash", "-lc", "free -m")
	run("/opt writable", true, "bash", "-lc", "test -w /opt && echo /opt is writable")
	run("Kafka ports free", true, "bash", "-lc", "if ss -tln | grep -E ':(9092|9093|7071)\\b'; then echo ports already in use; exit 1; else echo ports 9092, 9093, 7071 are free; fi")
	run("Sudo/systemctl access", false, "bash", "-lc", "systemctl list-unit-files >/dev/null && echo systemctl can list units")

	logs.WriteString("================================\n")
	if failed > 0 {
		msg := fmt.Sprintf("Prerequisite check failed: %d required checks failed, %d warnings", failed, warned)
		logs.WriteString(msg + "\n")
		return e.fail(t, msg+"\n"+logs.String()), nil
	}
	logs.WriteString(fmt.Sprintf("Prerequisite check passed with %d warnings\n", warned))
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logs.String()}, nil
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
