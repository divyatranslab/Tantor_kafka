package collect

import (
	"context"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/pkg/api"
	"os"
	"net"
	"runtime"
	"strings"
	"time"

	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/disk"
	"github.com/shirou/gopsutil/v3/mem"
)

// Collector handles gathering host metrics
type Collector struct {
	hostID string
	exec   executor.Executor
}

func NewCollector(hostID string) *Collector {
	return &Collector{
		hostID: hostID,
		exec:   executor.New(),
	}
}

// GetHeartbeat collects all system metrics for the heartbeat
func (c *Collector) GetHeartbeat() *api.HostHeartbeat {
	// Get real memory stats
	v, _ := mem.VirtualMemory()
	
	// Get real CPU usage (averaged over 1 second)
	cpuPercents, _ := cpu.Percent(time.Second, false)
	cpuUsage := 0.0
	if len(cpuPercents) > 0 {
		cpuUsage = cpuPercents[0]
	}

	// Get real disk stats (for the root path)
	d, _ := disk.Usage("/")
	diskTotal := int64(0)
	diskUsed := int64(0)
	if d != nil {
		diskTotal = int64(d.Total / 1024 / 1024 / 1024)
		diskUsed = int64(d.Used / 1024 / 1024 / 1024)
	}

	return &api.HostHeartbeat{
		HostID:      c.hostID,
		CPUUsagePct: cpuUsage,
		MemTotalMB:  int64(v.Total / 1024 / 1024),
		MemUsedMB:   int64(v.Used / 1024 / 1024),
		DiskTotalGB: diskTotal,
		DiskUsedGB:  diskUsed,
		JavaVersion: c.getJavaVersion(),
	}
}

// GetRegistration collects static details for initial registration
func (c *Collector) GetRegistration() *api.HostRegistration {
	hostname, _ := os.Hostname()
	
	return &api.HostRegistration{
		HostID:      c.hostID,
		Hostname:    hostname,
		IPAddresses: c.getLocalIPs(),
		OSDetails:   runtime.GOOS + "_" + runtime.GOARCH,
		AgentVer:    "1.0.0",
	}
}

// getLocalIPs dynamically fetches non-loopback IP addresses of the host
func (c *Collector) getLocalIPs() []string {
	var ips []string
	interfaces, err := net.Interfaces()
	if err != nil {
		return []string{"127.0.0.1"}
	}
	for _, i := range interfaces {
		addrs, err := i.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			var ip net.IP
			switch v := addr.(type) {
			case *net.IPNet:
				ip = v.IP
			case *net.IPAddr:
				ip = v.IP
			}
			if ip == nil || ip.IsLoopback() || ip.To4() == nil {
				continue
			}
			ips = append(ips, ip.String())
		}
	}
	if len(ips) == 0 {
		return []string{"127.0.0.1"}
	}
	return ips
}

func (c *Collector) getJavaVersion() string {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	// java -version prints to stderr usually
	_, stderr, err := c.exec.Run(ctx, "java", "-version")
	if err != nil {
		return "Unknown/Not Installed"
	}

	lines := strings.Split(stderr, "\n")
	if len(lines) > 0 {
		return strings.TrimSpace(lines[0])
	}
	return "Unknown"
}
