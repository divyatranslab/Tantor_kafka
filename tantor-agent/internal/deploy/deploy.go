package deploy

import (
	"context"
	"fmt"
	"log/slog"

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
