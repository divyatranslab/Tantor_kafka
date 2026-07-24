package main

import (
	"bufio"
	"context"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

type ExternalBrokerMetricsDTO struct {
	Hostname         string  `json:"hostname"`
	Bootstrap        string  `json:"bootstrap"`
	CPUUsagePct      float64 `json:"cpuUsagePct"`
	MemoryUsedMB     int64   `json:"memoryUsedMb"`
	MemoryTotalMB    int64   `json:"memoryTotalMb"`
	DiskUsedGB       int64   `json:"diskUsedGb"`
	DiskTotalGB      int64   `json:"diskTotalGb"`
	MessagesInPerSec float64 `json:"messagesInPerSec"`
	BytesInPerSec    float64 `json:"bytesInPerSec"`
}

type jmxCounterState struct {
	messages float64
	bytes    float64
	at       time.Time
	ready    bool
}

func resolveJMXURL(template string, cluster DiscoveredCluster) string {
	if template == "" {
		return ""
	}
	host := "127.0.0.1"
	port := extractFirstPort(cluster.BootstrapServers)
	resolved := strings.ReplaceAll(template, "{host}", host)
	resolved = strings.ReplaceAll(resolved, "{port}", port)
	return resolved
}

func readJMXCounters(ctx context.Context, httpClient *http.Client, endpoint string) (messages, bytesIn float64, ok bool) {
	if endpoint == "" {
		return 0, 0, false
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, 0, false
	}
	resp, err := httpClient.Do(req)
	if err != nil {
		return 0, 0, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return 0, 0, false
	}

	scanner := bufio.NewScanner(resp.Body)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		value, err := strconv.ParseFloat(fields[len(fields)-1], 64)
		if err != nil {
			continue
		}
		name := fields[0]
		switch {
		case strings.HasPrefix(name, "kafka_server_brokertopicmetrics_messagesinpersec_count"), strings.HasPrefix(name, "kafka_server_brokertopicmetrics_messagesinpersec_total"):
			messages += value
		case strings.HasPrefix(name, "kafka_server_brokertopicmetrics_bytesinpersec_count"), strings.HasPrefix(name, "kafka_server_brokertopicmetrics_bytesinpersec_total"):
			bytesIn += value
		}
	}
	return messages, bytesIn, scanner.Err() == nil
}

func counterRates(state *jmxCounterState, now time.Time, messages, bytesIn float64) (float64, float64) {
	if !state.ready {
		state.messages, state.bytes, state.at, state.ready = messages, bytesIn, now, true
		return 0, 0
	}
	seconds := now.Sub(state.at).Seconds()
	if seconds <= 0 {
		return 0, 0
	}
	var messageRate, byteRate float64
	if messages >= state.messages {
		messageRate = (messages - state.messages) / seconds
	}
	if bytesIn >= state.bytes {
		byteRate = (bytesIn - state.bytes) / seconds
	}
	state.messages, state.bytes, state.at = messages, bytesIn, now
	return messageRate, byteRate
}

func publishMetrics(ctx context.Context, client *APIClient, cfg RuntimeConfig, store *ClusterStore, states map[string]*jmxCounterState, logger *slog.Logger) {
	clusters := store.Get()
	if len(clusters) == 0 {
		return
	}
	hostMetrics := collectClusterMetrics(ctx, clusters[0])
	jmxHTTP := &http.Client{Timeout: 4 * time.Second}
	// A host may report separate broker and controller node records for the same
	// Kafka cluster. Metrics are host-level and must be published only once per
	// cluster/bootstrap/host to avoid duplicate heartbeat updates.
	seen := map[string]bool{}
	for _, cluster := range clusters {
		key := cluster.Name + "|" + cluster.BootstrapServers + "|" + cfg.NodeName
		if seen[key] {
			continue
		}
		seen[key] = true
		metrics := ExternalBrokerMetricsDTO{
			Hostname:      cfg.NodeName,
			Bootstrap:     cluster.BootstrapServers,
			CPUUsagePct:   hostMetrics.CPUUsagePct,
			MemoryUsedMB:  hostMetrics.MemoryUsedMB,
			MemoryTotalMB: hostMetrics.MemoryTotalMB,
			DiskUsedGB:    hostMetrics.DiskUsedGB,
			DiskTotalGB:   hostMetrics.DiskTotalGB,
		}
		if endpoint := resolveJMXURL(cfg.JMXMetricsURL, cluster); endpoint != "" {
			messages, bytesIn, ok := readJMXCounters(ctx, jmxHTTP, endpoint)
			if ok {
				state := states[cluster.Name]
				if state == nil {
					state = &jmxCounterState{}
					states[cluster.Name] = state
				}
				metrics.MessagesInPerSec, metrics.BytesInPerSec = counterRates(state, time.Now(), messages, bytesIn)
			}
		}
		query := url.Values{}
		query.Set("hostId", cfg.HostID)
		_, err := client.DoJSON(ctx, http.MethodPost, client.endpoint(externalAgentPath(cluster.Name, "/metrics")), query, metrics, nil)
		if err != nil {
			logger.Warn("metrics publish failed", "cluster", cluster.Name, "error", err)
		}
	}
}

func runMetricsLoop(ctx context.Context, client *APIClient, cfg RuntimeConfig, store *ClusterStore, logger *slog.Logger) {
	ticker := time.NewTicker(cfg.MetricsInterval)
	defer ticker.Stop()
	states := make(map[string]*jmxCounterState)
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			publishMetrics(ctx, client, cfg, store, states, logger)
		}
	}
}
