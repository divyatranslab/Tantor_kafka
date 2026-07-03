package main

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

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
	bootstrap := extractBootstrapServers(listenersRaw, hostname)
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

func extractBootstrapServers(listenersStr, hostname string) string {
	parts := strings.Split(listenersStr, ",")
	re := regexp.MustCompile(`://([^:]+):([0-9]+)`)
	var brokers []string
	for _, p := range parts {
		upper := strings.ToUpper(p)
		if strings.Contains(upper, "CONTROLLER") {
			continue
		}
		m := re.FindStringSubmatch(p)
		if len(m) > 2 {
			host := normalizeBootstrapHost(m[1], hostname)
			brokers = append(brokers, host+":"+m[2])
		}
	}
	return strings.Join(brokers, ",")
}

func normalizeBootstrapHost(host, fallback string) string {
	trimmed := strings.TrimSpace(host)
	switch strings.ToLower(trimmed) {
	case "", "localhost", "127.0.0.1", "0.0.0.0", "::", "[::]":
		if fallback != "" {
			return fallback
		}
	}
	return trimmed
}

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

func deriveClusterName(propsFile, hostname string) string {
	re := regexp.MustCompile(`[/\\](kafka[a-zA-Z0-9_-]*)[/\\]`)
	matches := re.FindAllStringSubmatch(propsFile, -1)
	if len(matches) > 0 {
		dirName := matches[0][1]
		return fmt.Sprintf("%s-%s", hostname, dirName)
	}

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

func findKafkaInstallRoot(propsFile string) string {
	dir := filepath.Dir(propsFile)
	for i := 0; i < 6; i++ {
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

func detectVersion(installPath string) string {
	libsDir := filepath.Join(installPath, "libs")
	entries, err := os.ReadDir(libsDir)
	if err != nil {
		return "Unknown"
	}

	re1 := regexp.MustCompile(`kafka-clients-([0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9]+)?)\.jar`)
	for _, e := range entries {
		m := re1.FindStringSubmatch(e.Name())
		if len(m) > 1 {
			return m[1]
		}
	}
	re2 := regexp.MustCompile(`kafka_[0-9.]+-([0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9]+)?)\.jar`)
	for _, e := range entries {
		m := re2.FindStringSubmatch(e.Name())
		if len(m) > 1 {
			return m[1]
		}
	}
	return "Unknown"
}

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
