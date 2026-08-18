package main

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/mem"
)

type ExternalBrokerMetricsDto struct {
	Hostname         string  `json:"hostname"`
	Bootstrap        string  `json:"bootstrap"`
	CpuUsagePct      float64 `json:"cpuUsagePct"`
	MemoryUsedMb     int64   `json:"memoryUsedMb"`
	MemoryTotalMb    int64   `json:"memoryTotalMb"`
	DiskUsedGb       int64   `json:"diskUsedGb"`
	DiskTotalGb      int64   `json:"diskTotalGb"`
	MessagesInPerSec float64 `json:"messagesInPerSec"`
	BytesInPerSec    float64 `json:"bytesInPerSec"`
}

func startMetricsStream(ctx context.Context, clients *agentHTTPClients, serverURL string, clusterName string, hostname string, bootstrap string, metricsURL string, interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		default:
		}
		metrics := ExternalBrokerMetricsDto{
			Hostname:  hostname,
			Bootstrap: bootstrap,
		}

		// CPU
		cpuPercents, _ := cpu.PercentWithContext(ctx, time.Second, false)
		if len(cpuPercents) > 0 {
			metrics.CpuUsagePct = cpuPercents[0]
		}

		// Memory
		v, _ := mem.VirtualMemoryWithContext(ctx)
		if v != nil {
			metrics.MemoryTotalMb = int64(v.Total / 1024 / 1024)
			metrics.MemoryUsedMb = int64(v.Used / 1024 / 1024)
		}

		// Disk
		d, _ := disk.UsageWithContext(ctx, "/")
		if d != nil {
			metrics.DiskTotalGb = int64(d.Total / 1024 / 1024 / 1024)
			metrics.DiskUsedGb = int64(d.Used / 1024 / 1024 / 1024)
		}

		// JMX/Prometheus metrics endpoint. Keep this configurable because client
		// VMs may expose exporters on non-default ports or disable them entirely.
		if strings.TrimSpace(metricsURL) != "" {
			resp, err := clients.metrics.do(ctx, http.MethodGet, metricsURL, "", nil, true)
			if err == nil && resp.StatusCode == 200 {
				body, _ := io.ReadAll(io.LimitReader(resp.Body, 2*1024*1024))
				metricsText := string(body)

				for _, line := range strings.Split(metricsText, "\n") {
					if strings.HasPrefix(line, "kafka_server_brokertopicmetrics_messagesinpersec_count") {
						parts := strings.Fields(line)
						if len(parts) > 1 {
							metrics.MessagesInPerSec, _ = strconv.ParseFloat(parts[len(parts)-1], 64)
						}
					} else if strings.HasPrefix(line, "kafka_server_brokertopicmetrics_bytesinpersec_count") {
						parts := strings.Fields(line)
						if len(parts) > 1 {
							metrics.BytesInPerSec, _ = strconv.ParseFloat(parts[len(parts)-1], 64)
						}
					}
				}
				closeResponse(resp)
			} else if resp != nil {
				closeResponse(resp)
			} else if err != nil && ctx.Err() == nil {
				fmt.Printf("Metrics scrape failed for cluster %s: %v\n", clusterName, err)
			}
		}

		// Send to Backend
		payloadBytes, _ := json.Marshal(metrics)
		apiURL := strings.TrimRight(serverURL, "/") + fmt.Sprintf("/api/v1/ui/external-clusters/discovery/%s/metrics", url.PathEscape(clusterName))
		postResp, err := clients.backend.do(ctx, http.MethodPost, apiURL, "application/json", payloadBytes, true)
		if err == nil && postResp != nil {
			if postResp.StatusCode != http.StatusOK {
				fmt.Printf("Metrics report failed for cluster %s: HTTP %d\n", clusterName, postResp.StatusCode)
			}
			closeResponse(postResp)
		} else if err != nil && ctx.Err() == nil {
			fmt.Printf("Metrics report failed for cluster %s: %v\n", clusterName, err)
		}

		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}
