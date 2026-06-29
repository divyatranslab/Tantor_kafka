package main

import (
	"fmt"
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
	ProcessRoles     string // broker, controller, broker,controller
	IsRunning        bool
	InstallPath      string
	PropsFile        string
	LogDirs          string
	Environment      string
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
