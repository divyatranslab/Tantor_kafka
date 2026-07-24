package main

import (
	"bufio"
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"
	"syscall"
	"time"
)

type HostMetrics struct {
	CPUUsagePct   float64
	MemoryUsedMB  int64
	MemoryTotalMB int64
	DiskUsedGB    int64
	DiskTotalGB   int64
}

type cpuSnapshot struct {
	idle  uint64
	total uint64
}

func readCPUSnapshot() (cpuSnapshot, error) {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return cpuSnapshot{}, err
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	if !scanner.Scan() {
		return cpuSnapshot{}, fmt.Errorf("/proc/stat is empty")
	}
	fields := strings.Fields(scanner.Text())
	if len(fields) < 5 || fields[0] != "cpu" {
		return cpuSnapshot{}, fmt.Errorf("unexpected /proc/stat format")
	}
	var values []uint64
	for _, field := range fields[1:] {
		v, err := strconv.ParseUint(field, 10, 64)
		if err != nil {
			return cpuSnapshot{}, err
		}
		values = append(values, v)
	}
	var total uint64
	for _, v := range values {
		total += v
	}
	idle := values[3]
	if len(values) > 4 {
		idle += values[4]
	}
	return cpuSnapshot{idle: idle, total: total}, nil
}

func cpuUsage(ctx context.Context) float64 {
	first, err := readCPUSnapshot()
	if err != nil {
		return 0
	}
	if !sleepContext(ctx, 200*time.Millisecond) {
		return 0
	}
	second, err := readCPUSnapshot()
	if err != nil || second.total <= first.total {
		return 0
	}
	totalDelta := second.total - first.total
	idleDelta := second.idle - first.idle
	return 100 * float64(totalDelta-idleDelta) / float64(totalDelta)
}

func memoryUsage() (usedMB, totalMB int64) {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	defer f.Close()
	var totalKB, availableKB int64
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 2 {
			continue
		}
		value, _ := strconv.ParseInt(fields[1], 10, 64)
		switch strings.TrimSuffix(fields[0], ":") {
		case "MemTotal":
			totalKB = value
		case "MemAvailable":
			availableKB = value
		}
	}
	if totalKB == 0 {
		return 0, 0
	}
	return (totalKB - availableKB) / 1024, totalKB / 1024
}

func diskUsage(path string) (usedGB, totalGB int64) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(path, &stat); err != nil {
		return 0, 0
	}
	total := stat.Blocks * uint64(stat.Bsize)
	available := stat.Bavail * uint64(stat.Bsize)
	used := total - available
	return int64(used / 1024 / 1024 / 1024), int64(total / 1024 / 1024 / 1024)
}

func firstExistingMetricPath(cluster DiscoveredCluster) string {
	for _, value := range []string{cluster.DataDirs, cluster.LogDirs} {
		for _, item := range strings.Split(value, ",") {
			item = strings.TrimSpace(item)
			if item == "" {
				continue
			}
			if info, err := os.Stat(item); err == nil && info.IsDir() {
				return item
			}
		}
	}
	return "/"
}

func collectClusterMetrics(ctx context.Context, cluster DiscoveredCluster) HostMetrics {
	usedMB, totalMB := memoryUsage()
	usedGB, totalGB := diskUsage(firstExistingMetricPath(cluster))
	return HostMetrics{
		CPUUsagePct:   cpuUsage(ctx),
		MemoryUsedMB:  usedMB,
		MemoryTotalMB: totalMB,
		DiskUsedGB:    usedGB,
		DiskTotalGB:   totalGB,
	}
}
