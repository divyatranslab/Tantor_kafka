package config

import (
	"bytes"
	"fmt"
	"net/url"
	"os"
	"strings"

	"gopkg.in/yaml.v3"
)

// Config represents the agent configuration
type Config struct {
	Environment string `yaml:"environment"`
	Agent       struct {
		HostID             string `yaml:"host_id"`
		AgentName          string `yaml:"agent_name"`
		ServerURL          string `yaml:"server_url"`
		CertFile           string `yaml:"cert_file"`
		KeyFile            string `yaml:"key_file"`
		CACert             string `yaml:"ca_cert"`
		PollInterval       int    `yaml:"poll_interval_seconds"`
		LogLevel           string `yaml:"log_level"`
		AllowInsecureHTTP  bool   `yaml:"allow_insecure_http"`
		InsecureSkipVerify bool   `yaml:"insecure_skip_verify"`
		HeartbeatInterval  int    `yaml:"heartbeat_interval_seconds"`
	} `yaml:"agent"`
	Paths struct {
		DataDir      string `yaml:"data_dir"`
		LogDir       string `yaml:"log_dir"`
		ArtifactsDir string `yaml:"artifacts_dir"`
	} `yaml:"paths"`
	// Legacy sections are intentionally retained only so that supported older
	// installations can be upgraded in place. They are not used for authentication
	// or transport decisions by this agent version.
	Auth      map[string]interface{} `yaml:"auth"`
	HTTP      map[string]interface{} `yaml:"http"`
	Privilege map[string]interface{} `yaml:"privilege"`
}

// Load reads the YAML configuration file
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("failed to read config file: %w", err)
	}

	var cfg Config
	decoder := yaml.NewDecoder(bytes.NewReader(data))
	decoder.KnownFields(true)
	if err := decoder.Decode(&cfg); err != nil {
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
	if cfg.Paths.LogDir == "" {
		cfg.Paths.LogDir = "/var/log/tantor-agent"
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}

	return &cfg, nil
}

func (cfg *Config) Validate() error {
	environment := strings.ToLower(strings.TrimSpace(cfg.Environment))
	if environment != "development" && environment != "sit" && environment != "uat" && environment != "production" {
		return fmt.Errorf("environment must be development, sit, uat, or production")
	}
	if strings.TrimSpace(cfg.Agent.HostID) == "" {
		return fmt.Errorf("agent.host_id is required")
	}
	if cfg.Agent.PollInterval < 1 || cfg.Agent.PollInterval > 300 {
		return fmt.Errorf("agent.poll_interval_seconds must be between 1 and 300")
	}
	if err := cfg.ValidateTransport(); err != nil {
		return err
	}
	serverURL, _ := url.Parse(strings.TrimSpace(cfg.Agent.ServerURL))
	if environment != "development" && isLoopbackHost(serverURL.Hostname()) {
		return fmt.Errorf("agent.server_url cannot use a loopback host outside development")
	}
	if environment != "development" && isPlaceholderHost(serverURL.Hostname()) {
		return fmt.Errorf("agent.server_url cannot use an example or placeholder host outside development")
	}
	return nil
}

func isLoopbackHost(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	return host == "localhost" || host == "::1" || strings.HasPrefix(host, "127.")
}

func isPlaceholderHost(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	return strings.HasSuffix(host, ".example") || strings.HasSuffix(host, ".invalid") ||
		strings.HasSuffix(host, ".test") || strings.ContainsAny(host, "<>")
}

func (cfg *Config) SafeServerEndpoint() string {
	parsed, err := url.Parse(strings.TrimSpace(cfg.Agent.ServerURL))
	if err != nil || parsed.Host == "" {
		return "<invalid>"
	}
	return parsed.Scheme + "://" + parsed.Host + parsed.EscapedPath()
}

// ValidateTransport enforces the production agent's HTTPS and mTLS contract.
func (cfg *Config) ValidateTransport() error {
	serverURL, err := url.Parse(strings.TrimSpace(cfg.Agent.ServerURL))
	if err != nil || serverURL.Host == "" {
		return fmt.Errorf("agent.server_url must be an absolute URL")
	}
	if serverURL.User != nil || serverURL.RawQuery != "" || serverURL.Fragment != "" {
		return fmt.Errorf("agent.server_url must not contain credentials, query parameters, or fragments")
	}
	if cfg.AllowsInsecureHTTP() {
		if serverURL.Scheme != "http" {
			return fmt.Errorf("agent.allow_insecure_http is valid only with an http server URL")
		}
		return nil
	}
	if serverURL.Scheme != "https" {
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

// AllowsInsecureHTTP exists solely for legacy development environments that do
// not yet terminate TLS. It requires an explicit opt-in and can never be used
// outside development; all SIT/UAT/production agents require HTTPS with mTLS.
func (cfg *Config) AllowsInsecureHTTP() bool {
	return strings.EqualFold(strings.TrimSpace(cfg.Environment), "development") && cfg.Agent.AllowInsecureHTTP
}
