package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"gopkg.in/yaml.v3"
)

// =========================================================================
// Configuration
// =========================================================================

type Config struct {
	Discovery struct {
		ServerURL      string   `yaml:"server_url"`
		ScanPaths      []string `yaml:"scan_paths"`
		Interval       string   `yaml:"interval"`
		NodeName       string   `yaml:"node_name"`
		RestartCommand string   `yaml:"restart_command"`
	} `yaml:"discovery"`
}

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

// =========================================================================
// Internal type representing one discovered Kafka installation
// =========================================================================

type DiscoveredCluster struct {
	Name             string
	Hostname         string
	BootstrapServers string
	KafkaVersion     string
	KafkaClusterID   string
	KafkaMode        string // KRaft or ZooKeeper
	Security         string // PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL
	BrokerCount      int
	NodeID           int
	ProcessRoles     string // broker, controller, broker,controller
	IsRunning        bool
	InstallPath      string
	PropsFile        string
	LogDirs          string
	Environment      string
}

// =========================================================================
// Main
// =========================================================================

func main() {
	configPath := flag.String("config", "discovery.yaml", "Path to configuration YAML")
	flag.Parse()

	fmt.Printf("Loading configuration from: %s\n", *configPath)
	raw, err := os.ReadFile(*configPath)
	if err != nil {
		fmt.Printf("Error reading config: %v\n", err)
		os.Exit(1)
	}

	var cfg Config
	if err := yaml.Unmarshal(raw, &cfg); err != nil {
		fmt.Printf("Error parsing YAML: %v\n", err)
		os.Exit(1)
	}

	serverURL := cfg.Discovery.ServerURL
	if serverURL == "" {
		fmt.Println("Error: server_url must be set in the YAML config.")
		os.Exit(1)
	}

	scanPaths := cfg.Discovery.ScanPaths
	if len(scanPaths) == 0 {
		scanPaths = []string{"/"}
	}

	intervalStr := cfg.Discovery.Interval

	hostname := cfg.Discovery.NodeName
	if hostname == "" {
		hostname, _ = os.Hostname()
	}
	environment := detectEnvironment(hostname)

	fmt.Println("======================================================")
	fmt.Println("       Tantor Discovery Agent - Multi-Cluster")
	fmt.Println("======================================================")
	fmt.Printf("  Server   : %s\n", serverURL)
	fmt.Printf("  Hostname : %s\n", hostname)
	fmt.Printf("  Scan dirs: %v\n", scanPaths)

	if intervalStr != "" {
		fmt.Printf("  Interval : %s (Continuous mode)\n", intervalStr)
	} else {
		fmt.Println("  Interval : None (One-shot mode)")
	}
	fmt.Println()

	if intervalStr != "" {
		// Continuous mode
		duration, err := time.ParseDuration(intervalStr)
		if err != nil {
			fmt.Printf("Invalid interval format: %v\n", err)
			os.Exit(1)
		}

		// Channel to send discovered clusters to the polling loop
		clustersChan := make(chan []DiscoveredCluster, 1)

		// Start polling loop in the background
		go pollForTasksLoop(serverURL, hostname, cfg.Discovery.RestartCommand, clustersChan)

		for {
			clusters := runDiscovery(serverURL, hostname, environment, scanPaths)
			// send non-blocking
			select {
			case clustersChan <- clusters:
			default:
			}

			fmt.Printf("\nWaiting %v until next discovery scan...\n\n", duration)
			time.Sleep(duration)
		}
	} else {
		// One-shot mode
		runDiscovery(serverURL, hostname, environment, scanPaths)
	}
}

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

func runDiscovery(serverURL, hostname, environment string, scanPaths []string) []DiscoveredCluster {

	// Step 1: build a set of running Kafka PIDs and their server.properties.
	runningProps := getRunningKafkaPropsFiles()
	fmt.Printf("Running Kafka processes: %d\n", len(runningProps))
	for props := range runningProps {
		fmt.Printf("  - %s\n", props)
	}

	// Step 2: scan the filesystem for all properties files.
	allProps := findAllConfigProperties(scanPaths)

	// MERGE running properties into allProps so we never miss a running cluster
	// even if it's installed in a strange path that wasn't in scanPaths
	for propsFile := range runningProps {
		found := false
		for _, p := range allProps {
			if p == propsFile {
				found = true
				break
			}
		}
		if !found {
			allProps = append(allProps, propsFile)
		}
	}

	fmt.Printf("\nFound %d config file(s) to process:\n", len(allProps))
	for _, p := range allProps {
		fmt.Printf("  - %s\n", p)
	}

	if len(allProps) == 0 {
		fmt.Println("\nNo Kafka installations found. Exiting.")
		return nil
	}

	// Step 3: parse each properties file into a discovered cluster.
	var clusters []DiscoveredCluster
	seen := map[string]bool{}

	for _, propsFile := range allProps {
		isRunning := runningProps[propsFile]
		dc := parseServerProperties(propsFile, isRunning, hostname, environment)
		if dc == nil {
			continue
		}

		// deduplicate by bootstrap servers
		if seen[dc.BootstrapServers] {
			continue
		}
		seen[dc.BootstrapServers] = true

		// check if the process is running
		dc.IsRunning = isRunning

		// try to detect version from the installation directory
		dc.KafkaVersion = detectVersion(dc.InstallPath)

		// try to read cluster.id from meta.properties in log dirs
		if dc.LogDirs != "" && dc.KafkaClusterID == "" {
			dc.KafkaClusterID = readClusterIDFromLogs(dc.LogDirs)
		}

		clusters = append(clusters, *dc)
	}

	// Step 4: print summary.
	fmt.Printf("\n========================================================\n")
	fmt.Printf("  Discovered %d unique Kafka cluster(s)\n", len(clusters))
	fmt.Printf("========================================================\n\n")
	for i, c := range clusters {
		running := "STOPPED"
		if c.IsRunning {
			running = "RUNNING"
		}
		fmt.Printf("  [%d] %s\n", i+1, c.Name)
		fmt.Printf("      Bootstrap : %s\n", c.BootstrapServers)
		fmt.Printf("      ClusterID : %s\n", c.KafkaClusterID)
		fmt.Printf("      Mode      : %s\n", c.KafkaMode)
		fmt.Printf("      Security  : %s\n", c.Security)
		fmt.Printf("      Version   : %s\n", c.KafkaVersion)
		fmt.Printf("      Brokers   : %d\n", c.BrokerCount)
		fmt.Printf("      Status    : %s\n", running)
		fmt.Printf("      Path      : %s\n\n", c.InstallPath)
	}

	// Step 5: register each cluster with the Tantor server.
	apiURL := strings.TrimRight(serverURL, "/") + "/api/v1/ui/external-clusters/discovery/report"
	ok, fail := 0, 0
	for _, c := range clusters {
		if registerCluster(apiURL, c) {
			ok++
		} else {
			fail++
		}
	}
	fmt.Printf("\nDone. Registered: %d  |  Failed: %d\n", ok, fail)
	return clusters
}

// =========================================================================
// Running-process detection
// =========================================================================

// getRunningKafkaPropsFiles returns a set of server.properties paths that
// belong to currently-running Kafka JVM processes.
func getRunningKafkaPropsFiles() map[string]bool {
	result := make(map[string]bool)

	out, err := exec.Command("pgrep", "-f", "kafka.Kafka").Output()
	if err != nil {
		// also try kafka.server.KafkaServer
		out, err = exec.Command("pgrep", "-f", "kafka.server.KafkaServer").Output()
	}
	if err != nil || len(strings.TrimSpace(string(out))) == 0 {
		return result
	}

	for _, pid := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		pid = strings.TrimSpace(pid)
		if pid == "" {
			continue
		}
		// Read /proc/<pid>/cmdline for the full command line
		cmdline := readProcessCmdline(pid)
		if cmdline == "" {
			continue
		}
		for _, token := range strings.Fields(cmdline) {
			if strings.HasSuffix(token, ".properties") {
				// Resolve to absolute path
				abs, err := filepath.Abs(token)
				if err == nil {
					result[abs] = true
				} else {
					result[token] = true
				}
			}
		}
	}
	return result
}

func readProcessCmdline(pid string) string {
	// Try /proc/<pid>/cmdline first (Linux)
	data, err := os.ReadFile(fmt.Sprintf("/proc/%s/cmdline", pid))
	if err == nil {
		// cmdline uses NUL separators
		return strings.ReplaceAll(string(data), "\x00", " ")
	}
	// Fallback to ps
	out, err := exec.Command("ps", "-p", pid, "-o", "args=").Output()
	if err == nil {
		return string(out)
	}
	return ""
}

// =========================================================================
// Filesystem scanning
// =========================================================================

// findAllConfigProperties walks each scan path looking for config files named
// "server.properties" or "broker.properties" inside any path that looks Kafka-related.
func findAllConfigProperties(scanPaths []string) []string {
	var results []string
	seen := map[string]bool{}

	for _, base := range scanPaths {
		info, err := os.Stat(base)
		if err != nil || !info.IsDir() {
			continue
		}

		_ = filepath.Walk(base, func(path string, fi os.FileInfo, err error) error {
			if err != nil {
				return nil
			}
			// Skip very deep directories (safety)
			rel, _ := filepath.Rel(base, path)
			if strings.Count(rel, string(os.PathSeparator)) > 8 {
				return filepath.SkipDir
			}
			// Skip certain directories to avoid infinite loops and permission issues
			if fi.IsDir() {
				name := fi.Name()
				if name == ".git" || name == "node_modules" || name == "__pycache__" {
					return filepath.SkipDir
				}
				// Skip OS level directories if we are scanning from root
				if base == "/" && (path == "/proc" || path == "/sys" || path == "/dev" || path == "/run" || path == "/boot" || path == "/tmp" || path == "/etc" || path == "/lib" || path == "/lib64" || path == "/usr/lib") {
					return filepath.SkipDir
				}
				return nil
			}
			if fi.Name() == "server.properties" || fi.Name() == "broker.properties" {
				abs, _ := filepath.Abs(path)
				if !seen[abs] {
					seen[abs] = true
					results = append(results, abs)
				}
			}
			return nil
		})
	}
	return results
}

// =========================================================================
// Parsing server.properties
// =========================================================================

func parseServerProperties(propsFile string, isRunning bool, hostname, defaultEnv string) *DiscoveredCluster {
	installPath := findKafkaInstallRoot(propsFile)
	versionStr := detectVersion(installPath)

	is4x := strings.HasPrefix(versionStr, "4.")

	// --- Apply exact version/mode logic to determine EXPECTED file ---
	expectedFile := ""

	fmt.Printf("\n--- CONFIGURATION FETCHING (Version: %s) ---\n", versionStr)
	if is4x {
		fmt.Println("Only KRaft mode is supported")
		fmt.Println("DIRECTORIES USED:")
		fmt.Println("    $KAFKA_HOME/config/")
		fmt.Println("        |-- broker.properties")
		fmt.Println("        `-- controller.properties")
		fmt.Println("ZooKeeper is not available")
		expectedFile = filepath.Join(installPath, "config", "broker.properties")
	} else {
		fmt.Println("Both ZooKeeper and KRaft supported")
		kraftBroker := filepath.Join(installPath, "config", "kraft", "broker.properties")

		clusterMode := "zookeeper"
		if fileExists(kraftBroker) {
			clusterMode = "kraft"
		}

		if clusterMode == "zookeeper" {
			fmt.Println("ZooKeeper mode selected")
			fmt.Println("DIRECTORIES USED:")
			fmt.Println("    $KAFKA_HOME/config/")
			fmt.Println("        |-- server.properties   (MAIN FILE)")
			fmt.Println("    $KAFKA_HOME/config/kraft/")
			fmt.Println("        |-- broker.properties   (NOT USED in ZK mode)")
			fmt.Println("        `-- controller.properties (NOT USED in ZK mode)")
			fmt.Println("Only server.properties is active")
			expectedFile = filepath.Join(installPath, "config", "server.properties")
		} else if clusterMode == "kraft" {
			fmt.Println("KRaft mode selected")
			fmt.Println("DIRECTORIES USED:")
			fmt.Println("    $KAFKA_HOME/config/kraft/")
			fmt.Println("        |-- broker.properties")
			fmt.Println("        `-- controller.properties")
			fmt.Println("No ZooKeeper dependency")
			expectedFile = kraftBroker
		} else {
			fmt.Println("Invalid cluster mode")
		}
	}
	fmt.Println("--------------------------------------------")

	// Skip if offline and this is not the expected configuration file
	if !isRunning && expectedFile != "" {
		absProps, _ := filepath.Abs(propsFile)
		absExpected, _ := filepath.Abs(expectedFile)
		if absProps != absExpected {
			return nil
		}
	}
	// -----------------------------------------------------------------

	content, err := os.ReadFile(propsFile)
	if err != nil {
		return nil
	}

	props := make(map[string]string)
	for _, line := range strings.Split(string(content), "\n") {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		idx := strings.Index(line, "=")
		if idx < 0 {
			continue
		}
		key := strings.TrimSpace(line[:idx])
		val := strings.TrimSpace(line[idx+1:])
		props[key] = val
	}

	// Determine bootstrap servers.
	listenersRaw := props["advertised.listeners"]
	if listenersRaw == "" {
		listenersRaw = props["listeners"]
	}
	if listenersRaw == "" {
		return nil // not a valid Kafka config
	}
	bootstrap := extractBootstrapServers(listenersRaw)
	if bootstrap == "" {
		return nil
	}

	// Mode: KRaft or ZooKeeper.
	kafkaMode := "ZooKeeper"
	processRoles := props["process.roles"]
	if processRoles != "" {
		kafkaMode = "KRaft"
	}

	// Security protocol.
	security := detectSecurity(listenersRaw, props["listener.security.protocol.map"])

	// Broker count from controller.quorum.voters.
	brokerCount := 1
	voters := props["controller.quorum.voters"]
	if voters != "" {
		brokerCount = strings.Count(voters, ",") + 1
	}

	nodeID := parseIntDefault(props["node.id"], 1)

	// Cluster ID.
	clusterID := props["cluster.id"]

	// Log dirs.
	logDirs := props["log.dirs"]
	if logDirs == "" {
		logDirs = props["log.dir"]
	}

	// Derive a meaningful name from the file path and port.
	clusterName := deriveClusterName(propsFile, hostname)
	clusterName = fmt.Sprintf("%s-%s", clusterName, extractFirstPort(bootstrap))

	return &DiscoveredCluster{
		Name:             clusterName,
		Hostname:         hostname,
		BootstrapServers: bootstrap,
		KafkaClusterID:   clusterID,
		KafkaMode:        kafkaMode,
		ProcessRoles:     processRoles,
		Security:         security,
		BrokerCount:      brokerCount,
		NodeID:           nodeID,
		IsRunning:        isRunning,
		InstallPath:      installPath,
		PropsFile:        propsFile,
		LogDirs:          logDirs,
		Environment:      defaultEnv,
	}
}

// =========================================================================
// Helper: extract bootstrap host:port pairs, skipping CONTROLLER listeners
// =========================================================================

func extractBootstrapServers(listenersStr string) string {
	parts := strings.Split(listenersStr, ",")
	re := regexp.MustCompile(`://([^:]+:[0-9]+)`)
	var brokers []string
	for _, p := range parts {
		upper := strings.ToUpper(p)
		if strings.Contains(upper, "CONTROLLER") {
			continue
		}
		m := re.FindStringSubmatch(p)
		if len(m) > 1 {
			brokers = append(brokers, m[1])
		}
	}
	return strings.Join(brokers, ",")
}

// =========================================================================
// Helper: detect security from listeners + protocol map
// =========================================================================

func detectSecurity(listenersStr, protocolMap string) string {
	upper := strings.ToUpper(listenersStr)
	if strings.Contains(upper, "SASL_SSL") {
		return "SASL_SSL"
	}
	if strings.Contains(upper, "SASL_PLAINTEXT") {
		return "SASL_PLAINTEXT"
	}
	if strings.Contains(upper, "SSL://") {
		return "SSL"
	}

	// Check the protocol map
	if protocolMap != "" {
		pmu := strings.ToUpper(protocolMap)
		if strings.Contains(pmu, "SASL_SSL") {
			return "SASL_SSL"
		}
		if strings.Contains(pmu, "SASL_PLAINTEXT") {
			return "SASL_PLAINTEXT"
		}
		if strings.Contains(pmu, "SSL") {
			return "SSL"
		}
	}
	return "PLAINTEXT"
}

// =========================================================================
// Helper: derive a human-readable cluster name from the properties path
// =========================================================================

func deriveClusterName(propsFile, hostname string) string {
	// Try to match directory names like kafka12, kafka2, kafka_prod etc.
	re := regexp.MustCompile(`[/\\](kafka[a-zA-Z0-9_-]*)[/\\]`)
	matches := re.FindAllStringSubmatch(propsFile, -1)
	if len(matches) > 0 {
		// Use the first kafka* directory name found
		dirName := matches[0][1]
		return fmt.Sprintf("%s-%s", hostname, dirName)
	}

	// Fallback: use parent directory of the properties file
	dir := filepath.Base(filepath.Dir(propsFile))
	return fmt.Sprintf("%s-%s", hostname, dir)
}

func extractFirstPort(bootstrap string) string {
	parts := strings.Split(bootstrap, ",")
	if len(parts) > 0 {
		hostPort := parts[0]
		idx := strings.LastIndex(hostPort, ":")
		if idx >= 0 && idx < len(hostPort)-1 {
			return hostPort[idx+1:]
		}
	}
	return "Unknown"
}

func parseIntDefault(value string, fallback int) int {
	if value == "" {
		return fallback
	}
	var parsed int
	if _, err := fmt.Sscanf(value, "%d", &parsed); err != nil {
		return fallback
	}
	return parsed
}

// =========================================================================
// Helper: find the Kafka installation root by walking up
// =========================================================================

func findKafkaInstallRoot(propsFile string) string {
	dir := filepath.Dir(propsFile)
	for i := 0; i < 6; i++ {
		// A Kafka install root typically has bin/ and libs/
		if dirExists(filepath.Join(dir, "libs")) || dirExists(filepath.Join(dir, "bin")) {
			return dir
		}
		dir = filepath.Dir(dir)
	}
	return filepath.Dir(propsFile)
}

func dirExists(path string) bool {
	fi, err := os.Stat(path)
	return err == nil && fi.IsDir()
}

func fileExists(path string) bool {
	info, err := os.Stat(path)
	if os.IsNotExist(err) {
		return false
	}
	return !info.IsDir()
}

// =========================================================================
// Helper: detect Kafka version from libs/ jars
// =========================================================================

func detectVersion(installPath string) string {
	libsDir := filepath.Join(installPath, "libs")
	entries, err := os.ReadDir(libsDir)
	if err != nil {
		return "Unknown"
	}

	// Preferred: kafka-clients-X.Y.Z.jar
	re1 := regexp.MustCompile(`kafka-clients-([0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9]+)?)\.jar`)
	for _, e := range entries {
		m := re1.FindStringSubmatch(e.Name())
		if len(m) > 1 {
			return m[1]
		}
	}
	// Fallback: kafka_2.13-X.Y.Z.jar
	re2 := regexp.MustCompile(`kafka_[0-9.]+-([0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9]+)?)\.jar`)
	for _, e := range entries {
		m := re2.FindStringSubmatch(e.Name())
		if len(m) > 1 {
			return m[1]
		}
	}
	return "Unknown"
}

// =========================================================================
// Helper: read cluster.id from meta.properties inside log dirs
// =========================================================================

func readClusterIDFromLogs(logDirsStr string) string {
	for _, dir := range strings.Split(logDirsStr, ",") {
		dir = strings.TrimSpace(dir)
		metaFile := filepath.Join(dir, "meta.properties")
		data, err := os.ReadFile(metaFile)
		if err != nil {
			continue
		}
		for _, line := range strings.Split(string(data), "\n") {
			line = strings.TrimSpace(line)
			if strings.HasPrefix(line, "cluster.id=") {
				return strings.TrimPrefix(line, "cluster.id=")
			}
		}
	}
	return ""
}

// =========================================================================
// Helper: detect environment from hostname
// =========================================================================

func detectEnvironment(hostname string) string {
	h := strings.ToLower(hostname)
	switch {
	case strings.Contains(h, "prod"):
		return "prod"
	case strings.Contains(h, "dev"):
		return "dev"
	case strings.Contains(h, "stag"), strings.Contains(h, "stg"):
		return "staging"
	case strings.Contains(h, "test"), strings.Contains(h, "qa"):
		return "test"
	case strings.Contains(h, "uat"):
		return "uat"
	}
	if v := os.Getenv("ENVIRONMENT"); v != "" {
		return v
	}
	if v := os.Getenv("APP_ENV"); v != "" {
		return v
	}
	return "unknown"
}

// =========================================================================
// Register a cluster with the Tantor server
// =========================================================================

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
