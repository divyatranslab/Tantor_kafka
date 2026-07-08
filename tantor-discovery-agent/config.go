package main

// =========================================================================
// Configuration
// =========================================================================

type Config struct {
	Discovery struct {
		HostID         string   `yaml:"host_id"`
		AgentName      string   `yaml:"agent_name"`
		ServerURL      string   `yaml:"server_url"`
		ScanPaths      []string `yaml:"scan_paths"`
		Interval       string   `yaml:"interval"`
		NodeName       string   `yaml:"node_name"`
		RestartCommand string   `yaml:"restart_command"`
	} `yaml:"discovery"`
}
