package main

import (
	"bytes"
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

func startMetricsStream(serverURL string, clusterName string, hostname string, bootstrap string, interval time.Duration) {
	go func() {
		for {
			metrics := ExternalBrokerMetricsDto{
				Hostname:  hostname,
				Bootstrap: bootstrap,
			}

			// CPU
			cpuPercents, _ := cpu.Percent(time.Second, false)
			if len(cpuPercents) > 0 {
				metrics.CpuUsagePct = cpuPercents[0]
			}

			// Memory
			v, _ := mem.VirtualMemory()
			if v != nil {
				metrics.MemoryTotalMb = int64(v.Total / 1024 / 1024)
				metrics.MemoryUsedMb = int64(v.Used / 1024 / 1024)
			}

			// Disk
			d, _ := disk.Usage("/")
			if d != nil {
				metrics.DiskTotalGb = int64(d.Total / 1024 / 1024 / 1024)
				metrics.DiskUsedGb = int64(d.Used / 1024 / 1024 / 1024)
			}

			// JMX Metrics (from Prometheus endpoint on port 7071)
			resp, err := http.Get("http://localhost:7071/metrics")
			if err == nil && resp.StatusCode == 200 {
				body, _ := io.ReadAll(resp.Body)
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
				resp.Body.Close()
			}

			// Send to Backend
			payloadBytes, _ := json.Marshal(metrics)
			apiURL := strings.TrimRight(serverURL, "/") + fmt.Sprintf("/api/v1/ui/external-clusters/discovery/%s/metrics", url.PathEscape(clusterName))
			postResp, err := http.Post(apiURL, "application/json", bytes.NewBuffer(payloadBytes))
			if err == nil && postResp != nil {
				postResp.Body.Close()
			}

			time.Sleep(interval)
		}
	}()
}
