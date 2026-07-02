package api

// HostRegistration Request for agent to register itself
type HostRegistration struct {
	HostID      string   `json:"host_id"`
	Hostname    string   `json:"hostname"`
	IPAddresses []string `json:"ip_addresses"`
	OSDetails   string   `json:"os_details"`
	AgentVer    string   `json:"agent_version"`
	AgentName   string   `json:"agent_name"`
	AgentPath   string   `json:"agent_path"`
}

// HostHeartbeat Metrics sent periodically
type HostHeartbeat struct {
	HostID      string  `json:"host_id"`
	CPUUsagePct float64 `json:"cpu_usage_pct"`
	MemTotalMB  int64   `json:"mem_total_mb"`
	MemUsedMB   int64   `json:"mem_used_mb"`
	DiskTotalGB int64   `json:"disk_total_gb"`
	DiskUsedGB  int64   `json:"disk_used_gb"`
	JavaVersion string  `json:"java_version"`
}

// Task represents a deployment or management task from the server
type Task struct {
	TaskID      string            `json:"task_id"`
	ClusterID   string            `json:"cluster_id,omitempty"`
	Command     string            `json:"command"` // e.g. INSTALL_KAFKA, START_SERVICE
	Parameters  map[string]string `json:"parameters"`
	ArtifactURL string            `json:"artifact_url,omitempty"`
	Checksum    string            `json:"checksum,omitempty"`
}

// TaskResult reports the result of a task execution
type TaskResult struct {
	TaskID       string `json:"task_id"`
	HostID       string `json:"host_id"`
	Status       string `json:"status"` // SUCCESS, FAILED
	LogOutput    string `json:"log_output"`
	ErrorMsg     string `json:"error_msg,omitempty"`
	CurrentStep  string `json:"current_step,omitempty"`
	FailedReason string `json:"failed_reason,omitempty"`
}
