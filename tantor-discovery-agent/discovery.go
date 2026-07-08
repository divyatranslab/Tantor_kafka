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
	Name             string
	Hostname         string
	BootstrapServers string
	KafkaVersion     string
	KafkaClusterID   string
	KafkaMode        string // KRaft or ZooKeeper
	Security         string // PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL
	BrokerCount      int
	NodeID           int
	ProcessRoles        string // broker, controller, broker,controller
	Listeners           string
	AdvertisedListeners string
	IsRunning           bool
	InstallPath         string
	PropsFile           string
	LogDirs             string
	Environment         string
}

func runDiscovery(serverURL, hostname, environment string, scanPaths []string, hostID, agentName string) []DiscoveredCluster {
	// Step 1: build a set of running Kafka PIDs and their server.properties.
	runningProps := getRunningKafkaPropsFiles()
	fmt.Printf("Running Kafka processes/services: %d\n", len(runningProps))
	for _, props := range runningProps {
		fmt.Printf("  - %s\n", props)
	}

	// Step 2: scan the filesystem for all properties files.
	allProps := findAllConfigProperties(scanPaths)

	// Since runningProps are now raw command lines / ExecStarts, we don't merge them directly into allProps.
	// allProps relies purely on filesystem scanning now, which is safer.

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
		isRunning := false
		baseProps := filepath.Base(propsFile)
		dirName := filepath.Base(filepath.Dir(propsFile)) // e.g. "config" or "kraft"
		
		for _, cmdline := range runningProps {
			if strings.Contains(cmdline, propsFile) {
				isRunning = true
				break
			}
			// Check if it's referenced relatively (e.g. config/server.properties)
			if strings.Contains(cmdline, filepath.Join(dirName, baseProps)) {
				isRunning = true
				break
			}
		}

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
		if registerCluster(apiURL, c, hostID, agentName) {
			ok++
		} else {
			fail++
		}
	}
	fmt.Printf("\nDone. Registered: %d  |  Failed: %d\n", ok, fail)
	return clusters
}
