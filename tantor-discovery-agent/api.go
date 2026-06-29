package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
)

// =========================================================================
// Payload sent to the Tantor server
// =========================================================================

type ExternalClusterPayload struct {
	Name             string `json:"name"`
	Environment      string `json:"environment"`
	BootstrapServers string `json:"bootstrapServers"`
	KafkaVersion     string `json:"kafkaVersion"`
	KafkaClusterID   string `json:"kafkaClusterId"`
	KafkaMode        string `json:"kafkaMode"`
	Security         string `json:"security"`
	BrokerCount      int    `json:"brokerCount"`
	NodeID           int    `json:"nodeId"`
	IsRunning        bool   `json:"isRunning"`
	InstallPath      string `json:"installPath"`
	LogDirs          string `json:"logDirs"`
	Hostname         string `json:"hostname"`
}

func externalAgentURL(serverURL, clusterName, suffix string) string {
	return strings.TrimRight(serverURL, "/") + "/api/v1/ui/external-clusters/discovery/" + url.PathEscape(clusterName) + suffix
}

func completeAgentTask(serverURL string, cluster DiscoveredCluster, hostname, status, message string) {
	completeURL := externalAgentURL(serverURL, cluster.Name, "/tasks/complete")
	query := url.Values{}
	query.Set("hostname", hostname)
	query.Set("bootstrap", cluster.BootstrapServers)
	completeURL += "?" + query.Encode()

	payload, _ := json.Marshal(map[string]string{
		"status":  status,
		"message": message,
	})
	resp, err := http.Post(completeURL, "application/json", bytes.NewBuffer(payload))
	if err == nil && resp != nil {
		resp.Body.Close()
	}
}

func registerCluster(apiURL string, c DiscoveredCluster) bool {
	payload := ExternalClusterPayload{
		Name:             c.Name,
		Environment:      c.Environment,
		BootstrapServers: c.BootstrapServers,
		KafkaVersion:     c.KafkaVersion,
		KafkaClusterID:   c.KafkaClusterID,
		KafkaMode:        c.KafkaMode,
		Security:         c.Security,
		BrokerCount:      c.BrokerCount,
		NodeID:           c.NodeID,
		IsRunning:        c.IsRunning,
		InstallPath:      c.InstallPath,
		LogDirs:          c.LogDirs,
		Hostname:         c.Hostname,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		fmt.Printf("  [failed] JSON error for %s: %v\n", c.Name, err)
		return false
	}

	resp, err := http.Post(apiURL, "application/json", bytes.NewBuffer(body))
	if err != nil {
		fmt.Printf("  [failed] Connection error for %s: %v\n", c.Name, err)
		return false
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusOK {
		fmt.Printf("  [ok] %s registered\n", c.Name)
		return true
	}

	respBody, _ := io.ReadAll(resp.Body)
	fmt.Printf("  [failed] %s HTTP %d: %s\n", c.Name, resp.StatusCode, string(respBody))
	return false
}
