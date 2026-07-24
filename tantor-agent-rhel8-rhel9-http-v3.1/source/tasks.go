package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

type AgentTask struct {
	TaskID         string            `json:"taskId"`
	Task           string            `json:"task"`
	ConfigFilePath string            `json:"configFilePath"`
	BackupDirPath  string            `json:"backupDirPath"`
	BackupFilePath string            `json:"backupFilePath"`
	ConfigChanges  map[string]string `json:"configChanges"`
	ServiceName    string            `json:"serviceName"`
}

type AgentTaskResult struct {
	TaskID  string            `json:"taskId"`
	Status  string            `json:"status"` // SUCCESS, FAILED
	Message string            `json:"message"`
	Data    map[string]string `json:"data"`
}

func runTaskPoller(ctx context.Context, client *APIClient, cfg RuntimeConfig, store *ClusterStore, logger *slog.Logger) {
	ticker := time.NewTicker(cfg.TaskPollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			clusters := store.Get()
			if len(clusters) == 0 {
				continue
			}
			pollForTaskGroup(ctx, client, cfg, clusters, logger)
		}
	}
}

func pollForTaskGroup(ctx context.Context, client *APIClient, cfg RuntimeConfig, records []DiscoveredCluster, logger *slog.Logger) {
	if len(records) == 0 {
		return
	}
	representative := records[0]
	for _, record := range records {
		if roleContains(record.ProcessRoles, "broker") {
			representative = record
			break
		}
	}
	query := url.Values{}
	query.Set("hostname", cfg.NodeName)
	query.Set("hostId", cfg.HostID)
	query.Set("bootstrap", representative.BootstrapServers)

	var task AgentTask
	status, err := client.DoJSON(ctx, http.MethodGet, client.endpoint(externalAgentPath(representative.Name, "/tasks")), query, nil, &task)
	if err != nil || status != http.StatusOK {
		return
	}

	if task.Task == "" || task.Task == "NONE" {
		return
	}

	logger.Info(fmt.Sprintf("Received %s task %s for cluster %s", task.Task, task.TaskID, representative.Name))

	result := AgentTaskResult{
		TaskID: task.TaskID,
		Status: "SUCCESS",
		Data:   make(map[string]string),
	}

	switch task.Task {
	case "backup_file":
		if err := executeBackupFile(task, representative); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "restore_backup":
		if err := executeRestoreBackup(task, representative); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "read_config":
		if props, err := executeReadConfig(task.ConfigFilePath, representative); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		} else {
			for k, v := range props {
				result.Data[k] = v
			}
		}
	case "write_config":
		if err := executeWriteConfig(task, representative); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "restart_service":
		if err := executeRestartService(task, "", representative, cfg.RestartWithSudo); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "service_status":
		if active, err := executeServiceStatus(task.ServiceName, representative, cfg.RestartWithSudo); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		} else {
			if active {
				result.Data["active"] = "true"
			} else {
				result.Data["active"] = "false"
			}
		}
	default:
		result.Status = "FAILED"
		result.Message = "unsupported task: " + task.Task
	}

	if err := completeTaskWithResult(ctx, client, cfg, representative, cfg.NodeName, result); err != nil {
		logger.Error("task completion report failed", "task_id", task.TaskID, "cluster", representative.Name, "error", err)
	}
}

func validatePathSafety(path string, cluster DiscoveredCluster) error {
	absReq, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("invalid path: %w", err)
	}
	absProps, err := filepath.Abs(cluster.PropsFile)
	if err != nil {
		return fmt.Errorf("invalid cluster path: %w", err)
	}
	if absReq != absProps {
		return fmt.Errorf("security violation: ConfigFilePath %s is not managed by this cluster", path)
	}
	return nil
}

func validateServiceSafety(serviceName string, cluster DiscoveredCluster) error {
	if serviceName == "" {
		return fmt.Errorf("serviceName cannot be empty")
	}
	if cluster.SystemdService == "" {
		return fmt.Errorf("no discovered systemd service mapped to this cluster")
	}
	if serviceName != cluster.SystemdService {
		return fmt.Errorf("security violation: ServiceName %s is not the discovered service for this cluster", serviceName)
	}
	return nil
}

func executeBackupFile(task AgentTask, cluster DiscoveredCluster) error {
	if task.ConfigFilePath == "" || task.BackupDirPath == "" || task.BackupFilePath == "" {
		return fmt.Errorf("missing configFilePath, backupDirPath, or backupFilePath")
	}

	if err := validatePathSafety(task.ConfigFilePath, cluster); err != nil {
		return err
	}

	if err := os.MkdirAll(task.BackupDirPath, 0755); err != nil {
		return fmt.Errorf("failed to create backup dir: %w", err)
	}

	src, err := os.Open(task.ConfigFilePath)
	if err != nil {
		return fmt.Errorf("failed to open source config: %w", err)
	}
	defer src.Close()

	srcInfo, err := src.Stat()
	if err != nil {
		return err
	}

	dst, err := os.OpenFile(task.BackupFilePath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, srcInfo.Mode())
	if err != nil {
		return fmt.Errorf("failed to create backup file: %w", err)
	}
	defer dst.Close()

	if _, err := io.Copy(dst, src); err != nil {
		return fmt.Errorf("failed to copy file: %w", err)
	}

	// Create metadata sidecar
	metaPath := task.BackupFilePath + ".meta.json"
	metaContent, _ := json.Marshal(task)
	_ = os.WriteFile(metaPath, metaContent, 0644)

	return nil
}

func executeRestoreBackup(task AgentTask, cluster DiscoveredCluster) error {
	if task.ConfigFilePath == "" || task.BackupFilePath == "" {
		return fmt.Errorf("missing configFilePath or backupFilePath")
	}

	if err := validatePathSafety(task.ConfigFilePath, cluster); err != nil {
		return err
	}

	src, err := os.Open(task.BackupFilePath)
	if err != nil {
		return fmt.Errorf("failed to open backup file: %w", err)
	}
	defer src.Close()

	srcInfo, err := src.Stat()
	if err != nil {
		return err
	}

	dst, err := os.OpenFile(task.ConfigFilePath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, srcInfo.Mode())
	if err != nil {
		return fmt.Errorf("failed to open config file for restore: %w", err)
	}
	defer dst.Close()

	if _, err := io.Copy(dst, src); err != nil {
		return fmt.Errorf("failed to copy backup back to config: %w", err)
	}

	return nil
}

func executeReadConfig(path string, cluster DiscoveredCluster) (map[string]string, error) {
	if path == "" {
		return nil, fmt.Errorf("no configFilePath provided")
	}

	if err := validatePathSafety(path, cluster); err != nil {
		return nil, err
	}

	content, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read config: %w", err)
	}

	props := make(map[string]string)
	lines := strings.Split(string(content), "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		parts := strings.SplitN(line, "=", 2)
		if len(parts) == 2 {
			props[strings.TrimSpace(parts[0])] = strings.TrimSpace(parts[1])
		}
	}

	return props, nil
}

func executeWriteConfig(task AgentTask, cluster DiscoveredCluster) error {
	if task.ConfigFilePath == "" || len(task.ConfigChanges) == 0 {
		return fmt.Errorf("missing configFilePath or configChanges")
	}

	if err := validatePathSafety(task.ConfigFilePath, cluster); err != nil {
		return err
	}

	content, err := os.ReadFile(task.ConfigFilePath)
	if err != nil {
		return fmt.Errorf("failed to read config file: %w", err)
	}

	lines := strings.Split(string(content), "\n")

	for key, value := range task.ConfigChanges {
		found := false
		for i, line := range lines {
			trimmed := strings.TrimSpace(line)
			if strings.HasPrefix(trimmed, "#") || !strings.Contains(trimmed, "=") {
				continue
			}
			existingKey := strings.TrimSpace(strings.SplitN(trimmed, "=", 2)[0])
			if existingKey == key {
				if value == "" {
					lines[i] = "" // Remove line if value is blank
				} else {
					lines[i] = key + "=" + value
				}
				found = true
				break
			}
		}
		if !found && value != "" {
			lines = append(lines, key+"="+value)
		}
	}

	// Filter out blank lines if we removed anything
	var newLines []string
	for _, l := range lines {
		if l != "" || (l == "" && !strings.Contains(l, "=")) { // Keep original blank lines maybe?
			newLines = append(newLines, l) // Wait, if we set lines[i] = "", it's a blank line.
		}
	}

	fileInfo, err := os.Stat(task.ConfigFilePath)
	if err != nil {
		return err
	}

	return os.WriteFile(task.ConfigFilePath, []byte(strings.Join(lines, "\n")), fileInfo.Mode())
}

func executeRestartService(task AgentTask, defaultRestartCommand string, cluster DiscoveredCluster, systemdUseSudo bool) error {
	var cmd *exec.Cmd
	if task.ServiceName != "" {
		if err := validateServiceSafety(task.ServiceName, cluster); err != nil {
			return err
		}
		fmt.Printf("Restarting systemd service: %s\n", task.ServiceName)
		if systemdUseSudo {
			cmd = exec.Command("sudo", "systemctl", "restart", task.ServiceName)
		} else {
			cmd = exec.Command("systemctl", "restart", task.ServiceName)
		}
	} else if defaultRestartCommand != "" {
		fmt.Printf("Executing default restart command: %s\n", defaultRestartCommand)
		cmdParts := strings.Fields(defaultRestartCommand)
		cmd = exec.Command(cmdParts[0], cmdParts[1:]...)
	} else {
		return fmt.Errorf("no serviceName or restartCommand provided")
	}

	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func executeServiceStatus(serviceName string, cluster DiscoveredCluster, systemdUseSudo bool) (bool, error) {
	if serviceName == "" {
		return false, fmt.Errorf("no serviceName provided")
	}
	if err := validateServiceSafety(serviceName, cluster); err != nil {
		return false, err
	}

	var cmd *exec.Cmd
	if systemdUseSudo {
		cmd = exec.Command("sudo", "systemctl", "is-active", "--quiet", serviceName)
	} else {
		cmd = exec.Command("systemctl", "is-active", "--quiet", serviceName)
	}
	err := cmd.Run()
	if err == nil {
		return true, nil
	}
	return false, nil
}
