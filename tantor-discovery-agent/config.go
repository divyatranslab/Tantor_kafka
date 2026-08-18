package main

import (
	"fmt"
	"time"
)

// =========================================================================
// Configuration
// =========================================================================

type Config struct {
	Discovery DiscoveryConfig `yaml:"discovery"`
}

type DiscoveryConfig struct {
	HostID                string     `yaml:"host_id"`
	AgentName             string     `yaml:"agent_name"`
	ServerURL             string     `yaml:"server_url"`
	ScanPaths             []string   `yaml:"scan_paths"`
	KafkaHome             string     `yaml:"kafka_home"`
	KafkaConfigFiles      []string   `yaml:"kafka_config_files"`
	KafkaDataDirs         string     `yaml:"kafka_data_dirs"`
	KafkaLogDirs          string     `yaml:"kafka_log_dirs"`
	Interval              string     `yaml:"interval"`
	NodeName              string     `yaml:"node_name"`
	RestartCommand        string     `yaml:"restart_command"`
	TaskPollInterval      string     `yaml:"task_poll_interval"`
	MetricsURL            string     `yaml:"metrics_url"`
	DisableMetrics        bool       `yaml:"disable_metrics"`
	SkipPrecheck          bool       `yaml:"skip_precheck"`
	SystemdUseSudo        bool       `yaml:"systemd_use_sudo"`
	TLSInsecureSkipVerify bool       `yaml:"tls_insecure_skip_verify"`
	CommandTimeout        string     `yaml:"command_timeout"`
	HTTP                  HTTPConfig `yaml:"http"`
}

type HTTPConfig struct {
	ConnectTimeout          string `yaml:"connect_timeout"`
	TLSHandshakeTimeout     string `yaml:"tls_handshake_timeout"`
	ResponseHeaderTimeout   string `yaml:"response_header_timeout"`
	RequestTimeout          string `yaml:"request_timeout"`
	RetryTotalTimeout       string `yaml:"retry_total_timeout"`
	RetryMaxAttempts        int    `yaml:"retry_max_attempts"`
	RetryInitialBackoff     string `yaml:"retry_initial_backoff"`
	RetryMaxBackoff         string `yaml:"retry_max_backoff"`
	CircuitFailureThreshold int    `yaml:"circuit_failure_threshold"`
	CircuitOpenDuration     string `yaml:"circuit_open_duration"`
}

type HTTPSettings struct {
	ConnectTimeout          time.Duration
	TLSHandshakeTimeout     time.Duration
	ResponseHeaderTimeout   time.Duration
	RequestTimeout          time.Duration
	RetryTotalTimeout       time.Duration
	RetryMaxAttempts        int
	RetryInitialBackoff     time.Duration
	RetryMaxBackoff         time.Duration
	CircuitFailureThreshold int
	CircuitOpenDuration     time.Duration
}

func (d DiscoveryConfig) EffectiveScanPaths() []string {
	if len(d.ScanPaths) > 0 {
		return d.ScanPaths
	}
	return []string{"/opt", "/opt_apb", "/app", "/srv", "/data", "/usr/local", "/usr/share", "/var/lib"}
}

func (d DiscoveryConfig) EffectiveTaskPollInterval() string {
	if d.TaskPollInterval != "" {
		return d.TaskPollInterval
	}
	return "5s"
}

func (d DiscoveryConfig) EffectiveMetricsURL() string {
	if d.MetricsURL != "" {
		return d.MetricsURL
	}
	return "http://localhost:7071/metrics"
}

func (d DiscoveryConfig) EffectiveCommandTimeout() (time.Duration, error) {
	return positiveDuration("command_timeout", d.CommandTimeout, 30*time.Second)
}

func (d DiscoveryConfig) EffectiveHTTPSettings() (HTTPSettings, error) {
	var settings HTTPSettings
	var err error
	if settings.ConnectTimeout, err = positiveDuration("http.connect_timeout", d.HTTP.ConnectTimeout, 3*time.Second); err != nil {
		return settings, err
	}
	if settings.TLSHandshakeTimeout, err = positiveDuration("http.tls_handshake_timeout", d.HTTP.TLSHandshakeTimeout, 5*time.Second); err != nil {
		return settings, err
	}
	if settings.ResponseHeaderTimeout, err = positiveDuration("http.response_header_timeout", d.HTTP.ResponseHeaderTimeout, 5*time.Second); err != nil {
		return settings, err
	}
	if settings.RequestTimeout, err = positiveDuration("http.request_timeout", d.HTTP.RequestTimeout, 10*time.Second); err != nil {
		return settings, err
	}
	if settings.RetryTotalTimeout, err = positiveDuration("http.retry_total_timeout", d.HTTP.RetryTotalTimeout, 25*time.Second); err != nil {
		return settings, err
	}
	if settings.RetryInitialBackoff, err = positiveDuration("http.retry_initial_backoff", d.HTTP.RetryInitialBackoff, 250*time.Millisecond); err != nil {
		return settings, err
	}
	if settings.RetryMaxBackoff, err = positiveDuration("http.retry_max_backoff", d.HTTP.RetryMaxBackoff, 2*time.Second); err != nil {
		return settings, err
	}
	if settings.CircuitOpenDuration, err = positiveDuration("http.circuit_open_duration", d.HTTP.CircuitOpenDuration, 30*time.Second); err != nil {
		return settings, err
	}

	settings.RetryMaxAttempts = d.HTTP.RetryMaxAttempts
	if settings.RetryMaxAttempts == 0 {
		settings.RetryMaxAttempts = 3
	}
	if settings.RetryMaxAttempts < 1 || settings.RetryMaxAttempts > 10 {
		return settings, fmt.Errorf("http.retry_max_attempts must be between 1 and 10")
	}
	if settings.RetryMaxBackoff < settings.RetryInitialBackoff {
		return settings, fmt.Errorf("http.retry_max_backoff must be greater than or equal to http.retry_initial_backoff")
	}

	settings.CircuitFailureThreshold = d.HTTP.CircuitFailureThreshold
	if settings.CircuitFailureThreshold == 0 {
		settings.CircuitFailureThreshold = 5
	}
	if settings.CircuitFailureThreshold < 1 {
		return settings, fmt.Errorf("http.circuit_failure_threshold must be greater than zero")
	}
	return settings, nil
}

func positiveDuration(name, value string, fallback time.Duration) (time.Duration, error) {
	if value == "" {
		return fallback, nil
	}
	duration, err := time.ParseDuration(value)
	if err != nil {
		return 0, fmt.Errorf("invalid %s: %w", name, err)
	}
	if duration <= 0 {
		return 0, fmt.Errorf("%s must be greater than zero", name)
	}
	return duration, nil
}
