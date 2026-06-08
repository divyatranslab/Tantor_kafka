package main

import (
	"context"
	"flag"
	"log/slog"
	"os"
	"os/signal"
	"syscall"

	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/collect"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/deploy"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/internal/task"
	"io.translab/tantor-agent/pkg/logger"
)

func main() {
	configPath := flag.String("config", "configs/agent.yaml", "Path to config file")
	flag.Parse()

	// 1. Load config
	cfg, err := config.Load(*configPath)
	if err != nil {
		panic("Failed to load config: " + err.Error())
	}

	// 2. Init logger
	logger.InitLogger(cfg.Agent.LogLevel)
	slog.Info("Starting Tantor Agent", "hostId", cfg.Agent.HostID, "serverUrl", cfg.Agent.ServerURL)

	// 3. Init components
	apiClient, err := client.NewAPIClient(cfg)
	if err != nil {
		slog.Error("Failed to initialize API client", "err", err)
		os.Exit(1)
	}

	collector := collect.NewCollector(cfg.Agent.HostID)
	deployEngine := deploy.NewEngine(cfg, apiClient, executor.New())

	taskEngine := task.NewEngine(cfg, apiClient, collector, deployEngine)

	// 4. Start background context
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Handle graceful shutdown
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigs
		slog.Info("Received shutdown signal")
		cancel()
	}()

	// 5. Run the task engine (blocks until ctx done)
	taskEngine.Start(ctx)

	slog.Info("Tantor Agent stopped cleanly")
}
