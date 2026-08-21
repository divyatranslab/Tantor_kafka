package config

import (
	"fmt"
	"net/url"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config represents the agent configuration
type Config struct {
	Agent struct {
		HostID       string `yaml:"host_id"`
		AgentName    string `yaml:"agent_name"`
		ServerURL    string `yaml:"server_url"`
		CertFile     string `yaml:"cert_file"`
		KeyFile      string `yaml:"key_file"`
		CACert       string `yaml:"ca_cert"`
		PollInterval int    `yaml:"poll_interval_seconds"`
		LogLevel     string `yaml:"log_level"`
	} `yaml:"agent"`
	Paths struct {
		DataDir      string `yaml:"data_dir"`
		LogDir       string `yaml:"log_dir"`
		ArtifactsDir string `yaml:"artifacts_dir"`
	} `yaml:"paths"`
}

// Load reads the YAML configuration file
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, fmt.Errorf("failed to unmarshal config: %w", err)
	}

	// Apply defaults
	if cfg.Agent.PollInterval == 0 {
		cfg.Agent.PollInterval = 10
	}
	if cfg.Agent.LogLevel == "" {
		cfg.Agent.LogLevel = "INFO"
	}
	if cfg.Paths.DataDir == "" {
		cfg.Paths.DataDir = "/var/lib/tantor-agent/data"
	}
	if cfg.Paths.ArtifactsDir == "" {
		cfg.Paths.ArtifactsDir = "/var/lib/tantor-agent/artifacts"
	}
	if err := cfg.ValidateTransport(); err != nil {
		return nil, err
	}

	return &cfg, nil
}

// ValidateTransport enforces the production agent's HTTPS and mTLS contract.
func (cfg *Config) ValidateTransport() error {
	serverURL, err := url.Parse(strings.TrimSpace(cfg.Agent.ServerURL))
	if err != nil || serverURL.Scheme != "https" || serverURL.Host == "" {
		return fmt.Errorf("agent.server_url must be an absolute https URL")
	}
	for name, value := range map[string]string{
		"agent.cert_file": cfg.Agent.CertFile,
		"agent.key_file":  cfg.Agent.KeyFile,
		"agent.ca_cert":   cfg.Agent.CACert,
	} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s is required for agent mTLS", name)
		}
	}
	return nil
}
