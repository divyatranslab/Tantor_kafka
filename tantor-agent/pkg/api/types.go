package api

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// HostRegistration Request for agent to register itself
type HostRegistration struct {
	AgentName   string   `json:"agent_name"`
	HostID      string   `json:"host_id"`
	Hostname    string   `json:"hostname"`
	IPAddresses []string `json:"ip_addresses"`
	OSDetails   string   `json:"os_details"`
	AgentVer    string   `json:"agent_version"`
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
	ClaimToken  string            `json:"claim_token"`
	ClusterID   string            `json:"cluster_id,omitempty"`
	Command     string            `json:"command"` // e.g. INSTALL_KAFKA, START_SERVICE
	Parameters  map[string]string `json:"parameters"`
	ArtifactURL string            `json:"artifact_url,omitempty"`
	Checksum    string            `json:"checksum,omitempty"`
}

// UnmarshalJSON accepts scalar parameter values from the management server
// while keeping the deployment engine's existing string-map contract. This
// prevents one numeric or boolean parameter from rejecting the entire poll
// response and blocking all subsequent tasks.
func (t *Task) UnmarshalJSON(data []byte) error {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(data, &raw); err != nil {
		return err
	}

	clean := make(map[string]json.RawMessage, len(raw))
	for key, value := range raw {
		if key != "parameters" {
			clean[key] = value
		}
	}
	cleanData, err := json.Marshal(clean)
	if err != nil {
		return err
	}
	type taskAlias Task
	var base taskAlias
	if err := json.Unmarshal(cleanData, &base); err != nil {
		return err
	}
	*t = Task(base)

	parameters, err := decodeTaskParameters(raw["parameters"])
	if err != nil {
		return err
	}
	t.Parameters = parameters
	return nil
}

func decodeTaskParameters(data json.RawMessage) (map[string]string, error) {
	parameters := map[string]string{}
	if len(data) == 0 || strings.TrimSpace(string(data)) == "null" {
		return parameters, nil
	}

	var rawValues map[string]json.RawMessage
	if err := json.Unmarshal(data, &rawValues); err != nil {
		return nil, fmt.Errorf("decode task parameters: %w", err)
	}
	for key, rawValue := range rawValues {
		var text string
		if err := json.Unmarshal(rawValue, &text); err == nil {
			parameters[key] = text
			continue
		}
		if strings.TrimSpace(string(rawValue)) == "null" {
			parameters[key] = ""
			continue
		}
		var compact bytes.Buffer
		if err := json.Compact(&compact, rawValue); err != nil {
			return nil, fmt.Errorf("decode task parameter %q: %w", key, err)
		}
		parameters[key] = compact.String()
	}
	return parameters, nil
}

// TaskResult reports the result of a task execution
type TaskResult struct {
	TaskID       string `json:"task_id"`
	ClaimToken   string `json:"claim_token"`
	HostID       string `json:"host_id"`
	Status       string `json:"status"` // SUCCESS, FAILED
	LogOutput    string `json:"log_output"`
	ErrorMsg     string `json:"error_msg,omitempty"`
	CurrentStep  string `json:"current_step,omitempty"`
	FailedReason string `json:"failed_reason,omitempty"`
}
