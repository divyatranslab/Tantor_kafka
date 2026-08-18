package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type PrecheckInstance struct {
	Instance    int    `json:"instance"`
	Source      string `json:"source"`
	State       string `json:"state"`
	Role        string `json:"role"`
	PID         string `json:"pid"`
	Ports       string `json:"ports"`
	Config      string `json:"config"`
	DataDir     string `json:"data_dir"`
	Version     string `json:"version"`
	ClusterID   string `json:"cluster_id"`
	NodeID      string `json:"node_id"`
	SystemdUnit string `json:"systemd_unit"`
}

type PrecheckResult struct {
	Hostname      string             `json:"hostname"`
	Timestamp     string             `json:"timestamp"`
	OS            string             `json:"os"`
	KafkaExists   bool               `json:"kafka_exists"`
	InstanceCount int                `json:"instance_count"`
	Status        string             `json:"status"`
	Instances     []PrecheckInstance `json:"instances"`
}

var (
	precheckInstances []PrecheckInstance
	seenPIDs          = make(map[string]bool)
	seenConfigs       = make(map[string]string)
	seenDirs          = make(map[string]bool)
)

func runPrecheck(ctx context.Context, commandTimeout time.Duration, jsonOutput bool) {
	precheckInstances = nil
	seenPIDs = make(map[string]bool)
	seenConfigs = make(map[string]string)
	seenDirs = make(map[string]bool)
	hostname, _ := os.Hostname()
	timestamp := time.Now().UTC().Format(time.RFC3339)
	osInfo := getOSInfo(ctx, commandTimeout)

	discoverProcessesPrecheck(ctx, commandTimeout)
	discoverFilesystemPrecheck(ctx, commandTimeout)
	discoverSystemdPrecheck(ctx, commandTimeout)
	discoverPortsPrecheck(ctx, commandTimeout)

	kafkaExists := len(precheckInstances) > 0

	if jsonOutput {
		status := "NODE_READY"
		if kafkaExists {
			status = "KAFKA_EXISTS"
		}
		res := PrecheckResult{
			Hostname:      hostname,
			Timestamp:     timestamp,
			OS:            osInfo,
			KafkaExists:   kafkaExists,
			InstanceCount: len(precheckInstances),
			Status:        status,
			Instances:     precheckInstances,
		}
		if len(res.Instances) == 0 {
			res.Instances = []PrecheckInstance{}
		}
		out, _ := json.MarshalIndent(res, "", "  ")
		fmt.Println(string(out))
		if kafkaExists {
			return
		}
		return
	}

	// ASCII Output
	fmt.Println()
	fmt.Println("╔══════════════════════════════════════════════════════════╗")
	fmt.Println("║        Kafka Node Pre-Deployment Check  v2.1             ║")
	fmt.Println("╚══════════════════════════════════════════════════════════╝")
	fmt.Printf("  %-12s %s\n", "Host:", hostname)
	fmt.Printf("  %-12s %s\n", "Timestamp:", timestamp)
	fmt.Printf("  %-12s %s\n", "OS:", osInfo)
	fmt.Println()

	if kafkaExists {
		fmt.Printf("  ✖  KAFKA EXISTS — %d server instance(s) detected\n", len(precheckInstances))
		fmt.Println("     Node is NOT ready for fresh deployment.")
		fmt.Println()

		for i, inst := range precheckInstances {
			fmt.Printf("  ┌─ Instance #%d ─────────────────────────────────────────\n", i+1)
			fmt.Printf("  │  %-18s %s\n", "State:", inst.State)
			fmt.Printf("  │  %-18s %s\n", "Source:", inst.Source)
			fmt.Printf("  │  %-18s %s\n", "Role:", inst.Role)
			fmt.Printf("  │  %-18s %s\n", "Version:", inst.Version)
			fmt.Printf("  │  %-18s %s\n", "PID:", inst.PID)
			fmt.Printf("  │  %-18s %s\n", "Port(s):", inst.Ports)
			fmt.Printf("  │  %-18s %s\n", "Config:", inst.Config)
			fmt.Printf("  │  %-18s %s\n", "Data Dir:", inst.DataDir)
			fmt.Printf("  │  %-18s %s\n", "Cluster ID:", inst.ClusterID)
			fmt.Printf("  │  %-18s %s\n", "Node ID:", inst.NodeID)
			if inst.SystemdUnit != "-" {
				fmt.Printf("  │  %-18s %s\n", "systemd:", inst.SystemdUnit)
			}
			fmt.Println("  └────────────────────────────────────────────────────────")
			fmt.Println()
		}

		fmt.Println("  Action Required:")
		fmt.Println("  • Fresh deployment → decommission all instances first.")
		fmt.Println("  • Existing node    → use 'Add External Cluster' instead.")
		fmt.Println()
		return
	} else {
		fmt.Println("  ✔  NODE IS CLEAN — READY FOR DEPLOYMENT")
		fmt.Println()
		fmt.Println("  All checks passed:")
		fmt.Println("  ✓  No Kafka server processes (broker/controller/zookeeper)")
		fmt.Println("  ✓  No Kafka install directories found")
		fmt.Println("  ✓  No Kafka systemd server units found")
		fmt.Println("  ✓  No Kafka server TCP listeners found")
		fmt.Println()
		return
	}
}

func getOSInfo(ctx context.Context, commandTimeout time.Duration) string {
	if b, err := os.ReadFile("/etc/os-release"); err == nil {
		for _, line := range strings.Split(string(b), "\n") {
			if strings.HasPrefix(line, "PRETTY_NAME=") {
				return strings.Trim(strings.TrimPrefix(line, "PRETTY_NAME="), "\"")
			}
		}
	}
	out, err := commandOutput(ctx, commandTimeout, "uname", "-sr")
	if err == nil {
		return strings.TrimSpace(string(out))
	}
	return "Unknown"
}

func addInstance(source, pid, role, ports, config, dataDir, version, clusterID, nodeID, systemdUnit, state string) {
	inst := PrecheckInstance{
		Instance:    len(precheckInstances) + 1,
		Source:      source,
		PID:         pid,
		Role:        role,
		Ports:       ports,
		Config:      config,
		DataDir:     dataDir,
		Version:     version,
		ClusterID:   clusterID,
		NodeID:      nodeID,
		SystemdUnit: systemdUnit,
		State:       state,
	}
	precheckInstances = append(precheckInstances, inst)
}

func detectRolePrecheck(cmdline, cfg string) string {
	if cfg != "" && cfg != "-" {
		b, err := os.ReadFile(cfg)
		if err == nil {
			content := string(b)
			pr := propPrecheck(content, "process.roles")
			if pr != "" {
				return pr
			}
		}
	}

	mainClass := ""
	for _, part := range strings.Fields(cmdline) {
		if strings.HasPrefix(part, "kafka.Kafka") {
			mainClass = "broker"
		} else if strings.Contains(part, "ZooKeeperMain") || strings.Contains(part, "QuorumPeer") {
			mainClass = "zookeeper"
		} else if strings.Contains(part, "Connect") {
			mainClass = "connect"
		} else if strings.Contains(part, "MirrorMaker") {
			mainClass = "mirrormaker"
		} else if strings.Contains(part, "SchemaRegistry") {
			mainClass = "schema-registry"
		}
	}
	if mainClass != "" {
		return mainClass
	}

	if cfg != "" && cfg != "-" {
		b, err := os.ReadFile(cfg)
		if err == nil {
			content := string(b)
			bid := propPrecheck(content, "broker.id")
			zc := propPrecheck(content, "zookeeper.connect")
			if bid != "" && zc != "" {
				return "broker(zk-mode)"
			}
			if bid != "" {
				return "broker"
			}
		}
	}
	return "unknown"
}

func propPrecheck(content, key string) string {
	for _, line := range strings.Split(content, "\n") {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, key+"=") || strings.HasPrefix(line, key+" ") {
			parts := strings.SplitN(line, "=", 2)
			if len(parts) == 2 {
				return strings.TrimSpace(parts[1])
			}
		}
	}
	return ""
}

func isServerRole(role string) bool {
	r := strings.ToLower(role)
	return strings.Contains(r, "broker") || strings.Contains(r, "controller") || strings.Contains(r, "zookeeper")
}

func isServerConfig(cfg string) bool {
	b, err := os.ReadFile(cfg)
	if err != nil {
		return false
	}
	content := string(b)
	return propPrecheck(content, "process.roles") != "" || propPrecheck(content, "broker.id") != "" || propPrecheck(content, "zookeeper.clientPort") != ""
}

func detectPortsPrecheck(cfg string) string {
	if cfg == "" || cfg == "-" {
		return "-"
	}
	b, err := os.ReadFile(cfg)
	if err != nil {
		return "-"
	}
	content := string(b)
	lval := propPrecheck(content, "listeners")
	if lval != "" {
		return extractPorts(lval)
	}
	p := propPrecheck(content, "port")
	if p != "" {
		return p
	}
	return "-"
}

func extractPorts(listeners string) string {
	var ports []string
	seen := make(map[string]bool)
	parts := strings.Split(listeners, ",")
	for _, p := range parts {
		idx := strings.LastIndex(p, ":")
		if idx != -1 && idx < len(p)-1 {
			port := p[idx+1:]
			if !seen[port] {
				seen[port] = true
				ports = append(ports, port)
			}
		}
	}
	if len(ports) == 0 {
		return "-"
	}
	return strings.Join(ports, ",")
}

func cfgToDatadirAndMeta(cfg string) (string, string, string) {
	if cfg == "" || cfg == "-" {
		return "-", "-", "-"
	}
	b, err := os.ReadFile(cfg)
	if err != nil {
		return "-", "-", "-"
	}
	content := string(b)
	datadir := propPrecheck(content, "log.dirs")
	if datadir == "" {
		datadir = propPrecheck(content, "metadata.log.dir")
	}
	if datadir == "" {
		datadir = "-"
	} else {
		datadir = strings.Split(datadir, ",")[0]
	}
	cid, nid := "-", "-"
	if datadir != "-" {
		c, n := readMeta(datadir)
		if c != "" {
			cid = c
		}
		if n != "" {
			nid = n
		}
	}
	return datadir, cid, nid
}

func readMeta(datadir string) (string, string) {
	meta := filepath.Join(datadir, "meta.properties")
	if _, err := os.Stat(meta); os.IsNotExist(err) {
		meta = filepath.Join(datadir, "metadata", "meta.properties")
	}
	b, err := os.ReadFile(meta)
	if err != nil {
		return "", ""
	}
	cid := propPrecheck(string(b), "cluster.id")
	nid := propPrecheck(string(b), "node.id")
	return cid, nid
}

func discoverProcessesPrecheck(ctx context.Context, commandTimeout time.Duration) {
	out, err := commandOutput(ctx, commandTimeout, "sh", "-c", "ps -eo pid= -o args= | grep -E '[j]ava.*[Kk]afka|[j]ava.*kafka'")
	if err != nil {
		return
	}
	lines := strings.Split(string(out), "\n")
	for _, line := range lines {
		if ctx.Err() != nil {
			return
		}
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) < 2 {
			continue
		}
		pid := parts[0]
		if seenPIDs[pid] {
			continue
		}

		cmdline := readProcessCmdline(ctx, commandTimeout, pid)
		var cfg string
		for _, token := range strings.Fields(cmdline) {
			if strings.HasSuffix(token, ".properties") {
				cfg = token
			}
		}

		role := detectRolePrecheck(cmdline, cfg)
		if !isServerRole(role) {
			continue
		}

		if cfg != "" && !isServerConfig(cfg) && role != "zookeeper" {
			continue
		}

		if cfg != "" && seenConfigs[cfg] != "" {
			seenConfigs[cfg] += "," + pid
			continue
		}
		if cfg != "" {
			seenConfigs[cfg] = pid
		} else {
			cfg = "-"
		}

		seenPIDs[pid] = true
		ports := detectPortsPrecheck(cfg)
		ver := "?"
		if cfg != "-" {
			ver = detectVersion(findKafkaInstallRoot(cfg))
		}
		datadir, cid, nid := cfgToDatadirAndMeta(cfg)

		addInstance("process", pid, role, ports, cfg, datadir, ver, cid, nid, "-", "running")
	}
}

func discoverFilesystemPrecheck(ctx context.Context, commandTimeout time.Duration) {
	roots := []string{"/opt", "/opt_apb", "/app", "/usr/local", "/usr/share", "/srv", "/data", "/var/lib"}
	var installDirs []string

	for _, root := range roots {
		if ctx.Err() != nil {
			return
		}
		if !dirExists(root) {
			continue
		}
		out, err := commandOutput(ctx, commandTimeout, "find", root, "-maxdepth", "6", "-name", "kafka-server-start.sh", "-not", "-path", "*/proc/*")
		if err == nil {
			for _, match := range strings.Split(string(out), "\n") {
				if match != "" {
					installDirs = append(installDirs, filepath.Dir(filepath.Dir(match)))
				}
			}
		}
	}

	for _, installDir := range installDirs {
		if ctx.Err() != nil {
			return
		}
		if seenDirs[installDir] {
			continue
		}
		seenDirs[installDir] = true

		ver := detectVersion(installDir)
		foundAny := false

		cfgDir := filepath.Join(installDir, "config")
		if dirExists(cfgDir) {
			out, err := commandOutput(ctx, commandTimeout, "find", cfgDir, "-maxdepth", "3", "-name", "*.properties")
			if err == nil {
				for _, cfg := range strings.Split(string(out), "\n") {
					if cfg == "" || strings.Contains(strings.ToLower(cfg), "example") || strings.Contains(strings.ToLower(cfg), "template") || strings.HasSuffix(cfg, ".bak") || strings.HasSuffix(cfg, ".orig") {
						continue
					}
					if !isServerConfig(cfg) {
						continue
					}
					if seenConfigs[cfg] != "" {
						continue
					}
					seenConfigs[cfg] = "filesystem"
					foundAny = true

					role := detectRolePrecheck("", cfg)
					ports := detectPortsPrecheck(cfg)
					datadir, cid, nid := cfgToDatadirAndMeta(cfg)

					addInstance("filesystem", "-", role, ports, cfg, datadir, ver, cid, nid, "-", "stopped")
				}
			}
		}

		if !foundAny {
			addInstance("filesystem", "-", "unknown", "-", cfgDir, "-", ver, "-", "-", "-", "stopped")
		}
	}
}

func discoverSystemdPrecheck(ctx context.Context, commandTimeout time.Duration) {
	if err := runCommand(ctx, commandTimeout, "systemctl", "--version"); err != nil {
		return
	}

	out1, _ := commandOutput(ctx, commandTimeout, "systemctl", "list-units", "--all", "--no-legend", "--plain")
	out2, _ := commandOutput(ctx, commandTimeout, "systemctl", "list-unit-files", "--no-legend", "--plain")

	lines := append(strings.Split(string(out1), "\n"), strings.Split(string(out2), "\n")...)

	seenUnits := make(map[string]bool)
	for _, line := range lines {
		if ctx.Err() != nil {
			return
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		unit := fields[0]
		if seenUnits[unit] {
			continue
		}
		if !strings.Contains(strings.ToLower(unit), "kafka") && !strings.Contains(strings.ToLower(unit), "zookeeper") {
			continue
		}
		seenUnits[unit] = true

		lowerUnit := strings.ToLower(unit)
		if strings.Contains(lowerUnit, "prometheus") || strings.Contains(lowerUnit, "exporter") || strings.Contains(lowerUnit, "monitor") || strings.Contains(lowerUnit, "grafana") || strings.Contains(lowerUnit, "telegraf") {
			continue
		}

		loadOut, _ := commandOutput(ctx, commandTimeout, "systemctl", "show", unit, "--property=LoadState", "--value")
		if strings.TrimSpace(string(loadOut)) == "not-found" {
			continue
		}

		activeOut, _ := commandOutput(ctx, commandTimeout, "systemctl", "show", unit, "--property=ActiveState", "--value")
		subOut, _ := commandOutput(ctx, commandTimeout, "systemctl", "show", unit, "--property=SubState", "--value")
		active := strings.TrimSpace(string(activeOut))
		sub := strings.TrimSpace(string(subOut))

		execStartOut, _ := commandOutput(ctx, commandTimeout, "systemctl", "show", unit, "--property=ExecStart", "--value")
		execStartStr := string(execStartOut)
		var cfg string
		for _, token := range strings.Fields(execStartStr) {
			if strings.HasSuffix(token, ".properties") {
				cfg = token
			}
		}

		role := "unknown"
		ports := "-"
		ver := "?"
		datadir, cid, nid := "-", "-", "-"

		if cfg != "" && fileExists(cfg) {
			if seenConfigs[cfg] != "" {
				continue
			}
			if !isServerConfig(cfg) {
				continue
			}
			seenConfigs[cfg] = "systemd:" + unit
			role = detectRolePrecheck("", cfg)
			ports = detectPortsPrecheck(cfg)
			ver = detectVersion(findKafkaInstallRoot(cfg))
			datadir, cid, nid = cfgToDatadirAndMeta(cfg)
		} else {
			if !strings.Contains(lowerUnit, "broker") && !strings.Contains(lowerUnit, "controller") && !strings.Contains(lowerUnit, "server") && !strings.Contains(lowerUnit, "zookeeper") {
				continue
			}
			cfg = "-"
		}

		state := "stopped"
		if active == "active" && sub == "running" {
			state = "running"
		}

		addInstance("systemd", "-", role, ports, cfg, datadir, ver, cid, nid, unit, state)
	}
}

func discoverPortsPrecheck(ctx context.Context, commandTimeout time.Duration) {
	var out []byte
	var err error
	out, err = commandOutput(ctx, commandTimeout, "ss", "-tlnp")
	if err != nil {
		out, err = commandOutput(ctx, commandTimeout, "netstat", "-tlnp")
		if err != nil {
			return
		}
	}

	coveredPorts := make(map[string]bool)
	for _, inst := range precheckInstances {
		for _, p := range strings.Split(inst.Ports, ",") {
			coveredPorts[p] = true
		}
	}

	for _, line := range strings.Split(string(out), "\n") {
		if ctx.Err() != nil {
			return
		}
		line = strings.TrimSpace(line)

		idx := strings.Index(line, ":")
		if idx == -1 {
			continue
		}

		portEnd := idx + 1
		for portEnd < len(line) && line[portEnd] >= '0' && line[portEnd] <= '9' {
			portEnd++
		}
		port := line[idx+1 : portEnd]
		if port == "" || coveredPorts[port] {
			continue
		}

		pid := ""
		if strings.Contains(line, "pid=") {
			pidIdx := strings.Index(line, "pid=")
			pidStart := pidIdx + 4
			pidEnd := pidStart
			for pidEnd < len(line) && line[pidEnd] >= '0' && line[pidEnd] <= '9' {
				pidEnd++
			}
			pid = line[pidStart:pidEnd]
		}
		if pid == "" || seenPIDs[pid] {
			continue
		}

		cmdline := readProcessCmdline(ctx, commandTimeout, pid)
		if !strings.Contains(strings.ToLower(cmdline), "kafka") {
			continue
		}

		var cfg string
		for _, token := range strings.Fields(cmdline) {
			if strings.HasSuffix(token, ".properties") {
				cfg = token
			}
		}

		role := detectRolePrecheck(cmdline, cfg)
		if !isServerRole(role) {
			continue
		}

		seenPIDs[pid] = true
		if cfg == "" {
			cfg = "-"
		}

		addInstance("port_scan", pid, role, port, cfg, "-", "?", "-", "-", "-", "running")
	}
}
