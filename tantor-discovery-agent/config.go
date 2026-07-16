package main

// =========================================================================
// Configuration
// =========================================================================

type Config struct {
	Discovery DiscoveryConfig `yaml:"discovery"`
}

type DiscoveryConfig struct {
	HostID                string   `yaml:"host_id"`
	AgentName             string   `yaml:"agent_name"`
	ServerURL             string   `yaml:"server_url"`
	ScanPaths             []string `yaml:"scan_paths"`
	KafkaHome             string   `yaml:"kafka_home"`
	KafkaConfigFiles      []string `yaml:"kafka_config_files"`
	KafkaDataDirs         string   `yaml:"kafka_data_dirs"`
	KafkaLogDirs          string   `yaml:"kafka_log_dirs"`
	Interval              string   `yaml:"interval"`
	NodeName              string   `yaml:"node_name"`
	RestartCommand        string   `yaml:"restart_command"`
	TaskPollInterval      string   `yaml:"task_poll_interval"`
	MetricsURL            string   `yaml:"metrics_url"`
	DisableMetrics        bool     `yaml:"disable_metrics"`
	SkipPrecheck          bool     `yaml:"skip_precheck"`
	SystemdUseSudo        bool     `yaml:"systemd_use_sudo"`
	TLSInsecureSkipVerify bool     `yaml:"tls_insecure_skip_verify"`
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
