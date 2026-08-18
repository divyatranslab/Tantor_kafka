package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"gopkg.in/yaml.v3"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	configPath := flag.String("config", "discovery.yaml", "Path to configuration YAML")
	jsonOutput := flag.Bool("json", false, "Output precheck results in JSON format")
	flag.Parse()

	if *jsonOutput {
		// If JSON was requested, exit after precheck to avoid mixing JSON with logs.
		runPrecheck(ctx, 30*time.Second, true)
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

	httpSettings, err := cfg.Discovery.EffectiveHTTPSettings()
	if err != nil {
		fmt.Printf("Invalid HTTP configuration: %v\n", err)
		os.Exit(1)
	}
	commandTimeout, err := cfg.Discovery.EffectiveCommandTimeout()
	if err != nil {
		fmt.Printf("Invalid command configuration: %v\n", err)
		os.Exit(1)
	}
	clients := newAgentHTTPClients(httpSettings, cfg.Discovery.TLSInsecureSkipVerify)

	if !cfg.Discovery.SkipPrecheck {
		runPrecheck(ctx, commandTimeout, false)
		if ctx.Err() != nil {
			return
		}
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

		var workers sync.WaitGroup
		workers.Add(1)
		go func() {
			defer workers.Done()
			pollForTasksLoop(
				ctx,
				clients,
				serverURL,
				hostname,
				cfg.Discovery.RestartCommand,
				cfg.Discovery.SystemdUseSudo,
				cfg.Discovery.EffectiveMetricsURL(),
				!cfg.Discovery.DisableMetrics,
				taskPollInterval,
				commandTimeout,
				clustersChan,
				&workers,
			)
		}()

		for ctx.Err() == nil {
			clusters := runDiscovery(ctx, clients, serverURL, hostname, environment, cfg.Discovery, commandTimeout)
			select {
			case clustersChan <- clusters:
			case <-ctx.Done():
			default:
			}

			fmt.Printf("\nWaiting %v until next discovery scan...\n\n", duration)
			timer := time.NewTimer(duration)
			select {
			case <-ctx.Done():
				if !timer.Stop() {
					<-timer.C
				}
			case <-timer.C:
			}
		}
		workers.Wait()
		fmt.Println("Discovery agent stopped.")
	} else {
		runDiscovery(ctx, clients, serverURL, hostname, environment, cfg.Discovery, commandTimeout)
	}
}
