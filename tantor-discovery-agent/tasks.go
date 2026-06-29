package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"strings"
	"time"
)

// =========================================================================
// Polling and task execution
// =========================================================================

func pollForTasksLoop(serverURL, hostname, restartCommand string, clustersChan <-chan []DiscoveredCluster) {
	var currentClusters []DiscoveredCluster
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	var metricsStarted = make(map[string]bool)

	for {
		select {
		case newClusters := <-clustersChan:
			currentClusters = newClusters
		case <-ticker.C:
			for _, c := range currentClusters {
				if !metricsStarted[c.Name] {
					startMetricsStream(serverURL, c.Name, hostname, c.BootstrapServers, 5*time.Second)
					metricsStarted[c.Name] = true
				}
				pollForTask(serverURL, c, hostname, restartCommand)
			}
		}
	}
}

func pollForTask(serverURL string, cluster DiscoveredCluster, hostname, restartCommand string) {
	apiURL := externalAgentURL(serverURL, cluster.Name, "/tasks")
	query := url.Values{}
	query.Set("hostname", hostname)
	query.Set("bootstrap", cluster.BootstrapServers)
	apiURL += "?" + query.Encode()

	resp, err := http.Get(apiURL)
	if err != nil || resp.StatusCode != http.StatusOK {
		return
	}
	defer resp.Body.Close()

	var result map[string]string
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return
	}

	task := result["task"]
	if task == "" || task == "NONE" {
		return
	}

	fmt.Printf("Received %s task for cluster %s\n", task, cluster.Name)
	status := "SUCCESS"
	message := ""

	switch task {
	case "RESTART":
		if err := runRestartCommand(restartCommand); err != nil {
			status = "FAILED"
			message = err.Error()
		}
	case "UPDATE_CONFIG":
		if err := updatePropertiesFile(cluster.PropsFile, result["configKey"], result["configValue"]); err != nil {
			status = "FAILED"
			message = err.Error()
		} else if strings.EqualFold(result["restart"], "true") {
			if err := runRestartCommand(restartCommand); err != nil {
				status = "FAILED"
				message = err.Error()
			}
		}
	default:
		status = "FAILED"
		message = "unsupported task: " + task
	}

	completeAgentTask(serverURL, cluster, hostname, status, message)
}

func runRestartCommand(restartCommand string) error {
	if restartCommand == "" {
		return fmt.Errorf("no restart_command configured")
	}
	fmt.Printf("Executing restart command: %s\n", restartCommand)
	cmdParts := strings.Fields(restartCommand)
	if len(cmdParts) == 0 {
		return fmt.Errorf("restart_command is empty")
	}
	cmd := exec.Command(cmdParts[0], cmdParts[1:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

func updatePropertiesFile(propsFile, key, value string) error {
	if propsFile == "" {
		return fmt.Errorf("no Kafka properties file discovered for this cluster")
	}
	if key == "" {
		return fmt.Errorf("config key is required")
	}

	content, err := os.ReadFile(propsFile)
	if err != nil {
		return fmt.Errorf("read properties file: %w", err)
	}

	lines := strings.Split(string(content), "\n")
	found := false
	for i, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "#") || !strings.Contains(trimmed, "=") {
			continue
		}
		existingKey := strings.TrimSpace(strings.SplitN(trimmed, "=", 2)[0])
		if existingKey == key {
			lines[i] = key + "=" + value
			found = true
			break
		}
	}
	if !found {
		lines = append(lines, key+"="+value)
	}

	backup := propsFile + ".tantor.bak"
	_ = os.WriteFile(backup, content, 0644)
	return os.WriteFile(propsFile, []byte(strings.Join(lines, "\n")), 0644)
}
