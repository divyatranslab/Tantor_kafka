package main

// =========================================================================
// Configuration
// =========================================================================

type Config struct {
	Discovery struct {
		ServerURL      string   `yaml:"server_url"`
		ScanPaths      []string `yaml:"scan_paths"`
		Interval       string   `yaml:"interval"`
		NodeName       string   `yaml:"node_name"`
		RestartCommand string   `yaml:"restart_command"`
	} `yaml:"discovery"`
}
