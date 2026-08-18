package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/mem"
)

// =========================================================================
// Payload sent to the Tantor server
// =========================================================================

type ExternalClusterPayload struct {
	HostID              string  `json:"hostId"`
	AgentName           string  `json:"agentName"`
	Name                string  `json:"name"`
	Environment         string  `json:"environment"`
	BootstrapServers    string  `json:"bootstrapServers"`
	KafkaVersion        string  `json:"kafkaVersion"`
	KafkaClusterID      string  `json:"kafkaClusterId"`
	KafkaMode           string  `json:"kafkaMode"`
	Security            string  `json:"security"`
	BrokerCount         int     `json:"brokerCount"`
	NodeID              int     `json:"nodeId"`
	IsRunning           bool    `json:"isRunning"`
	InstallPath         string  `json:"installPath"`
	ConfigFile          string  `json:"configFile"`
	DataDirs            string  `json:"dataDirs"`
	LogDirs             string  `json:"logDirs"`
	Hostname            string  `json:"hostname"`
	IPAddresses         string  `json:"ipAddresses"`
	Listeners           string  `json:"listeners"`
	AdvertisedListeners string  `json:"advertisedListeners"`
	ProcessRoles        string  `json:"processRoles"`
	CpuUsagePct         float64 `json:"cpuUsagePct"`
	MemoryUsedMb        int64   `json:"memoryUsedMb"`
	MemoryTotalMb       int64   `json:"memoryTotalMb"`
	DiskUsedGb          int64   `json:"diskUsedGb"`
	DiskTotalGb         int64   `json:"diskTotalGb"`
	CanExecuteTasks     bool    `json:"canExecuteTasks"`
}

func externalAgentURL(serverURL, clusterName, suffix string) string {
	return strings.TrimRight(serverURL, "/") + "/api/v1/ui/external-clusters/discovery/" + url.PathEscape(clusterName) + suffix
}

func firstNonBlank(values ...string) string {
	for _, value := range values {
		if strings.TrimSpace(value) != "" {
			return value
		}
	}
	return ""
}

func completeAgentTask(ctx context.Context, client *resilientHTTPClient, serverURL string, cluster DiscoveredCluster, hostname, status, message string) {
	completeURL := externalAgentURL(serverURL, cluster.Name, "/tasks/complete")
	query := url.Values{}
	query.Set("hostname", hostname)
	query.Set("bootstrap", cluster.BootstrapServers)
	completeURL += "?" + query.Encode()

	payload, _ := json.Marshal(map[string]string{
		"status":  status,
		"message": message,
	})
	resp, err := client.do(ctx, http.MethodPost, completeURL, "application/json", payload, true)
	if err != nil {
		fmt.Printf("  [failed] Task completion connection error: %v\n", err)
		return
	}
	defer closeResponse(resp)
	if resp.StatusCode != http.StatusOK {
		fmt.Printf("  [failed] Task completion HTTP %d\n", resp.StatusCode)
	}
}

func registerCluster(ctx context.Context, client *resilientHTTPClient, apiURL string, c DiscoveredCluster, hostID, agentName string) bool {
	payload := ExternalClusterPayload{
		HostID:              hostID,
		AgentName:           agentName,
		Name:                c.Name,
		Environment:         c.Environment,
		BootstrapServers:    c.BootstrapServers,
		KafkaVersion:        c.KafkaVersion,
		KafkaClusterID:      c.KafkaClusterID,
		KafkaMode:           c.KafkaMode,
		Security:            c.Security,
		BrokerCount:         c.BrokerCount,
		NodeID:              c.NodeID,
		IsRunning:           c.IsRunning,
		InstallPath:         c.InstallPath,
		ConfigFile:          c.PropsFile,
		DataDirs:            firstNonBlank(c.DataDirs, c.LogDirs),
		LogDirs:             c.LogDirs,
		Hostname:            c.Hostname,
		IPAddresses:         localIPAddressesJSON(),
		Listeners:           c.Listeners,
		AdvertisedListeners: c.AdvertisedListeners,
		ProcessRoles:        c.ProcessRoles,
		CanExecuteTasks:     true,
	}

	// Fetch CPU, RAM, and Disk telemetry
	cpuPercents, _ := cpu.PercentWithContext(ctx, time.Second, false)
	if len(cpuPercents) > 0 {
		payload.CpuUsagePct = cpuPercents[0]
	}
	if v, _ := mem.VirtualMemoryWithContext(ctx); v != nil {
		payload.MemoryTotalMb = int64(v.Total / 1024 / 1024)
		payload.MemoryUsedMb = int64(v.Used / 1024 / 1024)
	}
	if d, _ := disk.UsageWithContext(ctx, "/"); d != nil {
		payload.DiskTotalGb = int64(d.Total / 1024 / 1024 / 1024)
		payload.DiskUsedGb = int64(d.Used / 1024 / 1024 / 1024)
	}

	body, err := json.Marshal(payload)
	if err != nil {
		fmt.Printf("  [failed] JSON error for %s: %v\n", c.Name, err)
		return false
	}

	resp, err := client.do(ctx, http.MethodPost, apiURL, "application/json", body, true)
	if err != nil {
		fmt.Printf("  [failed] Connection error for %s: %v\n", c.Name, err)
		return false
	}
	defer closeResponse(resp)

	if resp.StatusCode == http.StatusOK {
		fmt.Printf("  [ok] %s registered\n", c.Name)
		return true
	}

	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 32*1024))
	fmt.Printf("  [failed] %s HTTP %d: %s\n", c.Name, resp.StatusCode, string(respBody))
	return false
}

func reportAgentHeartbeat(ctx context.Context, client *resilientHTTPClient, serverURL, hostname, hostID, agentName string) bool {
	payload := map[string]any{
		"hostId":          hostID,
		"agentName":       agentName,
		"hostname":        hostname,
		"ipAddresses":     localIPAddressesJSON(),
		"isRunning":       true,
		"canExecuteTasks": true,
	}

	body, err := json.Marshal(payload)
	if err != nil {
		fmt.Printf("  [failed] Agent heartbeat JSON error: %v\n", err)
		return false
	}

	apiURL := strings.TrimRight(serverURL, "/") + "/api/v1/ui/external-clusters/discovery/heartbeat"
	resp, err := client.do(ctx, http.MethodPost, apiURL, "application/json", body, true)
	if err != nil {
		fmt.Printf("  [failed] Agent heartbeat connection error: %v\n", err)
		return false
	}
	defer closeResponse(resp)

	if resp.StatusCode == http.StatusOK {
		fmt.Printf("  [ok] discovery agent heartbeat reported for %s\n", hostname)
		return true
	}

	respBody, _ := io.ReadAll(io.LimitReader(resp.Body, 32*1024))
	fmt.Printf("  [failed] Agent heartbeat HTTP %d: %s\n", resp.StatusCode, string(respBody))
	return false
}

func localIPAddressesJSON() string {
	addresses := localIPAddresses()
	body, err := json.Marshal(addresses)
	if err != nil {
		return "[]"
	}
	return string(body)
}

func localIPAddresses() []string {
	var values []string
	interfaces, err := net.Interfaces()
	if err != nil {
		return values
	}
	for _, iface := range interfaces {
		if iface.Flags&net.FlagUp == 0 || iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := iface.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch typed := addr.(type) {
			case *net.IPNet:
				ip = typed.IP
			case *net.IPAddr:
				ip = typed.IP
			}
			if ip == nil || ip.IsLoopback() || ip.IsLinkLocalUnicast() {
				continue
			}
			if ipv4 := ip.To4(); ipv4 != nil {
				values = append(values, ipv4.String())
				continue
			}
			values = append(values, ip.String())
		}
	}
	return values
}
