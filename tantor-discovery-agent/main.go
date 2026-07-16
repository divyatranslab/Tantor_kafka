package main

import (
	"crypto/tls"
	"flag"
	"fmt"
	"net/http"
	"os"
	"time"

	"gopkg.in/yaml.v3"
)

func main() {
	configPath := flag.String("config", "discovery.yaml", "Path to configuration YAML")
	jsonOutput := flag.Bool("json", false, "Output precheck results in JSON format")
	flag.Parse()

	if *jsonOutput {
		// If JSON was requested, exit after precheck to avoid mixing JSON with logs.
		runPrecheck(true)
		os.Exit(0)
	}

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

	configureHTTPTransport(cfg.Discovery.TLSInsecureSkipVerify)

	if !cfg.Discovery.SkipPrecheck {
		runPrecheck(false)
	}

	serverURL := cfg.Discovery.ServerURL
	if serverURL == "" {
		fmt.Println("Error: server_url must be set in the YAML config.")
		os.Exit(1)
	}

	scanPaths := cfg.Discovery.EffectiveScanPaths()
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
	if cfg.Discovery.KafkaHome != "" {
		fmt.Printf("  KafkaHome: %s\n", cfg.Discovery.KafkaHome)
	}
	if len(cfg.Discovery.KafkaConfigFiles) > 0 {
		fmt.Printf("  Configs  : %v\n", cfg.Discovery.KafkaConfigFiles)
	}
	fmt.Printf("  Scan dirs: %v\n", scanPaths)

	if intervalStr != "" {
		fmt.Printf("  Interval : %s (Continuous mode)\n", intervalStr)
	} else {
		fmt.Println("  Interval : None (One-shot mode)")
	}
	fmt.Println()

	if intervalStr != "" {
		duration, err := time.ParseDuration(intervalStr)
		if err != nil {
			fmt.Printf("Invalid interval format: %v\n", err)
			os.Exit(1)
		}

		clustersChan := make(chan []DiscoveredCluster, 1)
		taskPollInterval, err := time.ParseDuration(cfg.Discovery.EffectiveTaskPollInterval())
		if err != nil {
			fmt.Printf("Invalid task_poll_interval format: %v\n", err)
			os.Exit(1)
		}

		go pollForTasksLoop(
			serverURL,
			hostname,
			cfg.Discovery.RestartCommand,
			cfg.Discovery.SystemdUseSudo,
			cfg.Discovery.EffectiveMetricsURL(),
			!cfg.Discovery.DisableMetrics,
			taskPollInterval,
			clustersChan,
		)

		for {
			clusters := runDiscovery(serverURL, hostname, environment, cfg.Discovery)
			select {
			case clustersChan <- clusters:
			default:
			}

			fmt.Printf("\nWaiting %v until next discovery scan...\n\n", duration)
			time.Sleep(duration)
		}
	} else {
		runDiscovery(serverURL, hostname, environment, cfg.Discovery)
	}
}

func configureHTTPTransport(insecureSkipVerify bool) {
	transport, ok := http.DefaultTransport.(*http.Transport)
	if !ok {
		return
	}
	clone := transport.Clone()
	if insecureSkipVerify {
		clone.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}
	http.DefaultTransport = clone
}
