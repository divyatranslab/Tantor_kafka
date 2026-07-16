package main

import (
	"fmt"
	"path/filepath"
	"strings"
)

// =========================================================================
// Internal type representing one discovered Kafka installation
// =========================================================================

type DiscoveredCluster struct {
	Name                string
	Hostname            string
	BootstrapServers    string
	KafkaVersion        string
	KafkaClusterID      string
	KafkaMode           string // KRaft or ZooKeeper
	Security            string // PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL
	BrokerCount         int
	NodeID              int
	ProcessRoles        string // broker, controller, broker,controller
	Listeners           string
	AdvertisedListeners string
	IsRunning           bool
	InstallPath         string
	PropsFile           string
	DataDirs            string
	LogDirs             string
	Environment         string
	SystemdService      string
}

func runDiscovery(serverURL, hostname, environment string, discoveryCfg DiscoveryConfig) []DiscoveredCluster {
	scanPaths := discoveryCfg.EffectiveScanPaths()
	reportAgentHeartbeat(serverURL, hostname, discoveryCfg.HostID, discoveryCfg.AgentName)

	// Step 1: build a set of running Kafka PIDs and their server.properties.
	runningProps := getRunningKafkaPropsFiles()
	fmt.Printf("Running Kafka processes/services: %d\n", len(runningProps))
	for _, props := range runningProps {
		fmt.Printf("  - %s (Service: %s)\n", props.Cmdline, props.SystemdUnit)
	}

	// Step 2: Extract exact config file paths from running processes.
	var exactProps []string
	seenPath := map[string]bool{}
	addPropsFile := func(path string) {
		path = strings.TrimSpace(path)
		if path == "" || !fileExists(path) {
			return
		}
		absPath, err := filepath.Abs(path)
		if err != nil {
			absPath = path
		}
		if !seenPath[absPath] {
			exactProps = append(exactProps, absPath)
			seenPath[absPath] = true
		}
	}

	for _, processInfo := range runningProps {
		absPath := extractPropsPath(processInfo.Cmdline, processInfo.Cwd)
		if absPath != "" && !seenPath[absPath] {
			exactProps = append(exactProps, absPath)
			seenPath[absPath] = true
		}
	}

	// Step 3: prefer deployment-provided Kafka paths before broad filesystem scan.
	for _, configFile := range discoveryCfg.KafkaConfigFiles {
		addPropsFile(configFile)
	}
	for _, configFile := range kafkaHomeConfigCandidates(discoveryCfg.KafkaHome) {
		addPropsFile(configFile)
	}

	// Step 4: scan the filesystem for offline properties files.
	fsProps := findAllConfigProperties(scanPaths)
	for _, p := range fsProps {
		addPropsFile(p)
	}

	fmt.Printf("\nFound %d config file(s) to process:\n", len(exactProps))
	for _, p := range exactProps {
		fmt.Printf("  - %s\n", p)
	}

	if len(exactProps) == 0 {
		fmt.Println("\nNo Kafka installations found. Exiting.")
		return nil
	}

	// Step 4: parse each properties file into a discovered cluster.
	var clusters []DiscoveredCluster
	seen := map[string]bool{}

	for _, propsFile := range exactProps {
		isRunning := false
		baseProps := filepath.Base(propsFile)
		dirName := filepath.Base(filepath.Dir(propsFile)) // e.g. "config" or "kraft"

		systemdService := ""
		for _, processInfo := range runningProps {
			cmdline := processInfo.Cmdline
			if strings.Contains(cmdline, propsFile) {
				isRunning = true
				if processInfo.SystemdUnit != "" {
					systemdService = processInfo.SystemdUnit
				}
				break
			}
			// Check if it's referenced relatively (e.g. config/server.properties)
			if strings.Contains(cmdline, filepath.Join(dirName, baseProps)) {
				isRunning = true
				if processInfo.SystemdUnit != "" {
					systemdService = processInfo.SystemdUnit
				}
				break
			}
		}

		dc := parseServerProperties(propsFile, isRunning, hostname, environment, discoveryCfg)
		if dc == nil {
			continue
		}

		dc.SystemdService = systemdService

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
		if registerCluster(apiURL, c, discoveryCfg.HostID, discoveryCfg.AgentName) {
			ok++
		} else {
			fail++
		}
	}
	fmt.Printf("\nDone. Registered: %d  |  Failed: %d\n", ok, fail)
	return clusters
}

func kafkaHomeConfigCandidates(kafkaHome string) []string {
	kafkaHome = strings.TrimSpace(kafkaHome)
	if kafkaHome == "" {
		return nil
	}
	return []string{
		filepath.Join(kafkaHome, "config", "server.properties"),
		filepath.Join(kafkaHome, "config", "broker.properties"),
		filepath.Join(kafkaHome, "config", "controller.properties"),
		filepath.Join(kafkaHome, "config", "kraft", "server.properties"),
		filepath.Join(kafkaHome, "config", "kraft", "broker.properties"),
		filepath.Join(kafkaHome, "config", "kraft", "controller.properties"),
	}
}
