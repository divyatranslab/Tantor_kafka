package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// =========================================================================
// Polling and task execution
// =========================================================================

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

func pollForTasksLoop(
	ctx context.Context,
	clients *agentHTTPClients,
	serverURL, hostname, restartCommand string,
	systemdUseSudo bool,
	metricsURL string,
	metricsEnabled bool,
	taskPollInterval time.Duration,
	commandTimeout time.Duration,
	clustersChan <-chan []DiscoveredCluster,
	workers *sync.WaitGroup,
) {
	var currentClusters []DiscoveredCluster
	ticker := time.NewTicker(taskPollInterval)
	defer ticker.Stop()

	var metricsStarted = make(map[string]bool)

	for {
		select {
		case <-ctx.Done():
			return
		case newClusters := <-clustersChan:
			currentClusters = newClusters
		case <-ticker.C:
			for _, c := range currentClusters {
				if ctx.Err() != nil {
					return
				}
				if metricsEnabled && !metricsStarted[c.Name] {
					workers.Add(1)
					go func(cluster DiscoveredCluster) {
						defer workers.Done()
						startMetricsStream(ctx, clients, serverURL, cluster.Name, hostname, cluster.BootstrapServers, metricsURL, 5*time.Second)
					}(c)
					metricsStarted[c.Name] = true
				}
				pollForTask(ctx, clients.backend, serverURL, c, hostname, restartCommand, systemdUseSudo, commandTimeout)
			}
		}
	}
}

func pollForTask(ctx context.Context, client *resilientHTTPClient, serverURL string, cluster DiscoveredCluster, hostname, defaultRestartCommand string, systemdUseSudo bool, commandTimeout time.Duration) {
	apiURL := externalAgentURL(serverURL, cluster.Name, "/tasks")
	query := url.Values{}
	query.Set("hostname", hostname)
	query.Set("bootstrap", cluster.BootstrapServers)
	apiURL += "?" + query.Encode()

	// The server currently marks a task IN_PROGRESS when this GET succeeds, so
	// it must not be automatically retried until the protocol supports leases.
	resp, err := client.do(ctx, http.MethodGet, apiURL, "", nil, false)
	if err != nil {
		fmt.Printf("Task poll failed for cluster %s: %v\n", cluster.Name, err)
		return
	}
	defer closeResponse(resp)
	if resp.StatusCode != http.StatusOK {
		fmt.Printf("Task poll failed for cluster %s: HTTP %d\n", cluster.Name, resp.StatusCode)
		return
	}

	var task AgentTask
	if err := json.NewDecoder(resp.Body).Decode(&task); err != nil {
		return
	}

	if task.Task == "" || task.Task == "NONE" {
		return
	}

	fmt.Printf("Received %s task %s for cluster %s\n", task.Task, task.TaskID, cluster.Name)

	result := AgentTaskResult{
		TaskID: task.TaskID,
		Status: "SUCCESS",
		Data:   make(map[string]string),
	}

	switch task.Task {
	case "backup_file":
		if err := executeBackupFile(task, cluster); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "restore_backup":
		if err := executeRestoreBackup(task, cluster); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "read_config":
		if props, err := executeReadConfig(task.ConfigFilePath, cluster); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		} else {
			for k, v := range props {
				result.Data[k] = v
			}
		}
	case "write_config":
		if err := executeWriteConfig(task, cluster); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "restart_service":
		if err := executeRestartService(ctx, task, defaultRestartCommand, cluster, systemdUseSudo, commandTimeout); err != nil {
			result.Status = "FAILED"
			result.Message = err.Error()
		}
	case "service_status":
		if active, err := executeServiceStatus(ctx, task.ServiceName, cluster, systemdUseSudo, commandTimeout); err != nil {
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

	completeTaskWithResult(ctx, client, serverURL, cluster, hostname, result)
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

func executeRestartService(ctx context.Context, task AgentTask, defaultRestartCommand string, cluster DiscoveredCluster, systemdUseSudo bool, commandTimeout time.Duration) error {
	var name string
	var args []string
	if task.ServiceName != "" {
		if err := validateServiceSafety(task.ServiceName, cluster); err != nil {
			return err
		}
		fmt.Printf("Restarting systemd service: %s\n", task.ServiceName)
		if systemdUseSudo {
			name, args = "sudo", []string{"-n", "systemctl", "restart", task.ServiceName}
		} else {
			name, args = "systemctl", []string{"restart", task.ServiceName}
		}
	} else if defaultRestartCommand != "" {
		fmt.Printf("Executing default restart command: %s\n", defaultRestartCommand)
		cmdParts := strings.Fields(defaultRestartCommand)
		name, args = cmdParts[0], cmdParts[1:]
		if name == "sudo" && (len(args) == 0 || args[0] != "-n") {
			args = append([]string{"-n"}, args...)
		}
	} else {
		return fmt.Errorf("no serviceName or restartCommand provided")
	}

	return runAttachedCommand(ctx, commandTimeout, name, args...)
}

func executeServiceStatus(ctx context.Context, serviceName string, cluster DiscoveredCluster, systemdUseSudo bool, commandTimeout time.Duration) (bool, error) {
	if serviceName == "" {
		return false, fmt.Errorf("no serviceName provided")
	}
	if err := validateServiceSafety(serviceName, cluster); err != nil {
		return false, err
	}

	var err error
	if systemdUseSudo {
		err = runCommand(ctx, commandTimeout, "sudo", "-n", "systemctl", "is-active", "--quiet", serviceName)
	} else {
		err = runCommand(ctx, commandTimeout, "systemctl", "is-active", "--quiet", serviceName)
	}
	if err == nil {
		return true, nil
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return false, err
	}
	return false, nil
}

func completeTaskWithResult(ctx context.Context, client *resilientHTTPClient, serverURL string, cluster DiscoveredCluster, hostname string, result AgentTaskResult) {
	completeURL := externalAgentURL(serverURL, cluster.Name, "/tasks/complete")
	query := url.Values{}
	query.Set("hostname", hostname)
	query.Set("bootstrap", cluster.BootstrapServers)
	completeURL += "?" + query.Encode()

	payload, _ := json.Marshal(result)
	resp, err := client.do(ctx, http.MethodPost, completeURL, "application/json", payload, true)
	if err != nil {
		fmt.Printf("Task completion failed for cluster %s: %v\n", cluster.Name, err)
		return
	}
	defer closeResponse(resp)
	if resp.StatusCode != http.StatusOK {
		fmt.Printf("Task completion failed for cluster %s: HTTP %d\n", cluster.Name, resp.StatusCode)
	}
}
