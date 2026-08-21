package task

import (
	"context"
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/collect"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/deploy"
	"io.translab/tantor-agent/pkg/api"
)

// Engine handles background polling and task dispatching
type Engine struct {
	cfg          *config.Config
	client       *client.APIClient
	collector    *collect.Collector
	deployEngine *deploy.Engine
	taskMu       sync.Mutex
}

func NewEngine(cfg *config.Config, c *client.APIClient, col *collect.Collector, deployEngine *deploy.Engine) *Engine {
	return &Engine{
		cfg:          cfg,
		client:       c,
		collector:    col,
		deployEngine: deployEngine,
	}
}

// Start begins the agent loops
func (e *Engine) Start(ctx context.Context) {
	// 1. Initial Registration
	e.register()

	// 2. Start Loops
	pollTicker := time.NewTicker(time.Duration(e.cfg.Agent.PollInterval) * time.Second)
	heartbeatTicker := time.NewTicker(30 * time.Second) // fixed 30s for heartbeat

	defer pollTicker.Stop()
	defer heartbeatTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			slog.Info("Task engine shutting down")
			return
		case <-heartbeatTicker.C:
			e.sendHeartbeat()
		case <-pollTicker.C:
			e.pollTasks()
		}
	}
}

func (e *Engine) register() {
	reg := e.collector.GetRegistration()
	err := e.client.RegisterHost(reg)
	if err != nil {
		slog.Error("Failed to register host, will keep trying...", "err", err)
	} else {
		slog.Info("Host successfully registered with management server")
	}
}

func (e *Engine) sendHeartbeat() {
	hb := e.collector.GetHeartbeat()
	err := e.client.SendHeartbeat(hb)
	if err != nil {
		slog.Warn("Failed to send heartbeat", "err", err)
		if strings.Contains(err.Error(), "404") {
			slog.Info("Server returned 404, host must have been deleted. Re-registering...")
			e.register()
		}
	}
}

func (e *Engine) pollTasks() {
	tasks, err := e.client.PollTasks()
	if err != nil {
		slog.Error("Failed to poll tasks", "err", err)
		return
	}

	for _, t := range tasks {
		slog.Info("Received task", "taskId", t.TaskID, "command", t.Command)
	}
	go e.executeTasks(tasks)
}

func (e *Engine) executeTasks(tasks []api.Task) {
	e.taskMu.Lock()
	defer e.taskMu.Unlock()

	for _, t := range tasks {
		e.executeTask(t)
	}
}

func (e *Engine) executeTask(t api.Task) {
	// Report intermediate status: RUNNING
	if err := e.client.ReportTaskResult(&api.TaskResult{
		TaskID:     t.TaskID,
		ClaimToken: t.ClaimToken,
		HostID:     e.cfg.Agent.HostID,
		Status:     "RUNNING",
	}); err != nil {
		slog.Error("Failed to report RUNNING status", "err", err)
	}

	result, err := e.deployEngine.Execute(context.Background(), &t)
	if err != nil {
		slog.Error("Task execution failed with system error", "err", err)
		if result == nil {
			result = &api.TaskResult{
				TaskID:   t.TaskID,
				HostID:   e.cfg.Agent.HostID,
				Status:   "FAILED",
				ErrorMsg: err.Error(),
			}
		}
	}
	if result != nil {
		result.ClaimToken = t.ClaimToken
	}

	slog.Info("Task executed", "taskId", t.TaskID, "status", result.Status)

	var reportErr error
	for i := 0; i < 5; i++ {
		reportErr = e.client.ReportTaskResult(result)
		if reportErr == nil {
			break
		}
		slog.Warn("Failed to report task result, retrying...", "attempt", i+1, "err", reportErr)
		time.Sleep(time.Duration(2<<i) * time.Second) // 2s, 4s, 8s, 16s, 32s
	}

	if reportErr != nil {
		slog.Error("CRITICAL: Failed to persist task result permanently. Saving to local state.", "err", reportErr)
		
		spoolDir := filepath.Join(e.cfg.Paths.DataDir, "failed_tasks")
		if err := os.MkdirAll(spoolDir, 0755); err == nil {
			if data, err := json.Marshal(result); err == nil {
				os.WriteFile(filepath.Join(spoolDir, t.TaskID+".json"), data, 0644)
			}
		}
	}
}
