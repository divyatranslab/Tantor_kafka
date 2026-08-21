package main

import (
	"fmt"
	"net/url"
	"strings"
	"time"
)

// =========================================================================
// Configuration
// =========================================================================

type Config struct {
	Discovery DiscoveryConfig `yaml:"discovery"`
}

type DiscoveryConfig struct {
	Environment      string     `yaml:"environment"`
	HostID           string     `yaml:"host_id"`
	AgentName        string     `yaml:"agent_name"`
	ServerURL        string     `yaml:"server_url"`
	ScanPaths        []string   `yaml:"scan_paths"`
	KafkaHome        string     `yaml:"kafka_home"`
	KafkaConfigFiles []string   `yaml:"kafka_config_files"`
	KafkaDataDirs    string     `yaml:"kafka_data_dirs"`
	KafkaLogDirs     string     `yaml:"kafka_log_dirs"`
	Interval         string     `yaml:"interval"`
	NodeName         string     `yaml:"node_name"`
	RestartCommand   string     `yaml:"restart_command"`
	TaskPollInterval string     `yaml:"task_poll_interval"`
	MetricsURL       string     `yaml:"metrics_url"`
	DisableMetrics   bool       `yaml:"disable_metrics"`
	SkipPrecheck     bool       `yaml:"skip_precheck"`
	SystemdUseSudo   bool       `yaml:"systemd_use_sudo"`
	TLSCACert        string     `yaml:"tls_ca_cert"`
	TLSClientCert    string     `yaml:"tls_client_cert"`
	TLSClientKey     string     `yaml:"tls_client_key"`
	CommandTimeout   string     `yaml:"command_timeout"`
	HTTP             HTTPConfig `yaml:"http"`
}

func (d DiscoveryConfig) Validate() error {
	environment := strings.ToLower(strings.TrimSpace(d.Environment))
	if environment != "development" && environment != "sit" && environment != "uat" && environment != "production" {
		return fmt.Errorf("discovery.environment must be development, sit, uat, or production")
	}
	if strings.TrimSpace(d.HostID) == "" || strings.TrimSpace(d.AgentName) == "" {
		return fmt.Errorf("discovery.host_id and discovery.agent_name are required")
	}
	if err := d.ValidateTransport(); err != nil {
		return err
	}
	serverURL, _ := url.Parse(strings.TrimSpace(d.ServerURL))
	if environment != "development" && isDiscoveryLoopback(serverURL.Hostname()) {
		return fmt.Errorf("discovery.server_url cannot use a loopback host outside development")
	}
	if environment != "development" && isDiscoveryPlaceholder(serverURL.Hostname()) {
		return fmt.Errorf("discovery.server_url cannot use an example or placeholder host outside development")
	}
	if d.Interval != "" {
		if _, err := positiveDuration("interval", d.Interval, 0); err != nil {
			return err
		}
	}
	if _, err := positiveDuration("task_poll_interval", d.EffectiveTaskPollInterval(), 0); err != nil {
		return err
	}
	if !d.DisableMetrics {
		metrics, err := url.Parse(strings.TrimSpace(d.EffectiveMetricsURL()))
		if err != nil || metrics.Host == "" || (metrics.Scheme != "http" && metrics.Scheme != "https") {
			return fmt.Errorf("discovery.metrics_url must be an absolute HTTP(S) URL when metrics are enabled")
		}
	}
	return nil
}

func isDiscoveryLoopback(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	return host == "localhost" || host == "::1" || strings.HasPrefix(host, "127.")
}

func isDiscoveryPlaceholder(host string) bool {
	host = strings.ToLower(strings.TrimSpace(host))
	return strings.HasSuffix(host, ".example") || strings.HasSuffix(host, ".invalid") ||
		strings.HasSuffix(host, ".test") || strings.ContainsAny(host, "<>")
}

func (d DiscoveryConfig) SafeServerEndpoint() string {
	parsed, err := url.Parse(strings.TrimSpace(d.ServerURL))
	if err != nil || parsed.Host == "" {
		return "<invalid>"
	}
	return parsed.Scheme + "://" + parsed.Host + parsed.EscapedPath()
}

func (d DiscoveryConfig) ValidateTransport() error {
	serverURL, err := url.Parse(strings.TrimSpace(d.ServerURL))
	if err != nil || serverURL.Scheme != "https" || serverURL.Host == "" {
		return fmt.Errorf("discovery.server_url must be an absolute https URL")
	}
	if serverURL.User != nil || serverURL.RawQuery != "" || serverURL.Fragment != "" {
		return fmt.Errorf("discovery.server_url must not contain credentials, query parameters, or fragments")
	}
	for name, value := range map[string]string{
		"discovery.tls_ca_cert":     d.TLSCACert,
		"discovery.tls_client_cert": d.TLSClientCert,
		"discovery.tls_client_key":  d.TLSClientKey,
	} {
		if strings.TrimSpace(value) == "" {
			return fmt.Errorf("%s is required for discovery-agent mTLS", name)
		}
	}
	return nil
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
