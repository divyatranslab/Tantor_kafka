package deploy

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"io.translab/tantor-agent/pkg/api"
)

type schemaDirectoryCandidate struct {
	Path      string `json:"path"`
	Exists    bool   `json:"exists"`
	Writable  bool   `json:"writable"`
	FreeBytes int64  `json:"free_bytes"`
}

func (e *Engine) schemaDirectoryCandidates(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	paths := []string{"/opt", "/opt_apb", "/apache", "/var/lib"}
	if configured := csvValues(t.Parameters["candidate_paths"]); len(configured) > 0 {
		paths = configured
	}
	candidates := make([]schemaDirectoryCandidate, 0, len(paths))
	for _, path := range paths {
		clean, err := safeSchemaPath(path)
		if err != nil {
			continue
		}
		_, _, existsErr := e.exec.RunSudo(ctx, "test", "-d", clean)
		_, _, writableErr := e.exec.RunSudo(ctx, "test", "-w", clean)
		candidates = append(candidates, schemaDirectoryCandidate{
			Path: clean, Exists: existsErr == nil, Writable: writableErr == nil,
			FreeBytes: schemaFreeBytes(ctx, e, clean),
		})
	}
	payload, _ := json.Marshal(map[string]any{"directories": candidates})
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: string(payload)}, nil
}

func (e *Engine) precheckSchema(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	type check struct {
		name, detail string
		ok           bool
	}
	checks := make([]check, 0, 12)
	add := func(name, detail string, ok bool) { checks = append(checks, check{name, detail, ok}) }

	javaHome, _, javaErr := e.exec.Run(ctx, "bash", "-c", `for p in /usr/bin/java /usr/lib/jvm/*/bin/java /usr/java/*/bin/java /opt/*/bin/java; do if [ -x "$p" ]; then dirname "$(dirname "$(readlink -f "$p")")"; exit 0; fi; done; exit 1`)
	add("Java", schemaFirstNonBlank(strings.TrimSpace(javaHome), "Java runtime was not found"), javaErr == nil)
	add("Schema Registry artifact", "artifact URL and SHA-256 checksum are present", strings.TrimSpace(t.ArtifactURL) != "" && strings.TrimSpace(t.Checksum) != "")

	port := schemaFirstNonBlank(strings.TrimSpace(t.Parameters["rest_port"]), "8081")
	listener, listenErr := net.Listen("tcp", net.JoinHostPort("", port))
	if listenErr == nil {
		_ = listener.Close()
	}
	portDetail := "port " + port + " is available"
	if listenErr != nil {
		portDetail = "port " + port + " is already in use"
	}
	add("REST port", portDetail, listenErr == nil)

	kafkaInstall := filepath.Clean(strings.TrimSpace(t.Parameters["kafka_install_dir"]))
	for _, key := range []string{"install_dir", "config_dir", "log_dir", "working_dir"} {
		value := strings.TrimSpace(t.Parameters[key])
		clean, pathErr := safeSchemaPath(value)
		collidesWithKafka := kafkaInstall != "." && kafkaInstall != "" &&
			(clean == kafkaInstall || strings.HasPrefix(clean+string(filepath.Separator), kafkaInstall+string(filepath.Separator)) ||
				strings.HasPrefix(kafkaInstall+string(filepath.Separator), clean+string(filepath.Separator)))
		valid := pathErr == nil && !collidesWithKafka
		detail := clean
		if pathErr != nil {
			detail = pathErr.Error()
		} else if collidesWithKafka {
			detail += " overlaps the Kafka installation directory"
		}
		parent := nearestExistingParent(ctx, e, clean)
		if valid {
			_, _, writableErr := e.exec.RunSudo(ctx, "test", "-w", parent)
			if writableErr != nil {
				valid = false
				detail += " is not creatable"
			}
		}
		if valid && schemaFreeBytes(ctx, e, parent) < 512*1024*1024 {
			valid = false
			detail += " has less than 512 MiB free"
		}
		add(key, detail, valid)
	}

	bootstrap := csvValues(t.Parameters["bootstrap_servers"])
	reachable := len(bootstrap) > 0
	for _, endpoint := range bootstrap {
		address := endpointAddress(endpoint)
		conn, dialErr := net.DialTimeout("tcp", address, 3*time.Second)
		if dialErr != nil {
			reachable = false
			break
		}
		_ = conn.Close()
	}
	deferKafka := strings.EqualFold(t.Parameters["allow_deferred_kafka"], "true")
	brokerDetail := "configured broker endpoints are reachable"
	if !reachable && deferKafka {
		brokerDetail = "deferred until Kafka deployment completes"
	}
	add("Kafka brokers", brokerDetail, reachable || deferKafka)
	if !deferKafka {
		add("Kafka Admin API", "backend Kafka Admin API verification passed", strings.EqualFold(t.Parameters["admin_api_verified"], "true"))
	}
	add("Version compatibility", "Kafka and Schema Registry versions were supplied", strings.TrimSpace(t.Parameters["schema_version"]) != "" && strings.TrimSpace(t.Parameters["kafka_version"]) != "")

	availableMemoryMB := int64(0)
	if meminfo, _, memErr := e.exec.Run(ctx, "cat", "/proc/meminfo"); memErr == nil {
		for _, line := range strings.Split(meminfo, "\n") {
			fields := strings.Fields(line)
			if len(fields) >= 2 && fields[0] == "MemAvailable:" {
				kb, _ := strconv.ParseInt(fields[1], 10, 64)
				availableMemoryMB = kb / 1024
				break
			}
		}
	}
	add("Available RAM", fmt.Sprintf("%d MiB available (minimum 1024 MiB)", availableMemoryMB), availableMemoryMB >= 1024)

	var logs strings.Builder
	failed := 0
	for _, item := range checks {
		if item.ok {
			logs.WriteString(fmt.Sprintf("[PASS] %s: %s\n", item.name, item.detail))
		} else {
			failed++
			logs.WriteString(fmt.Sprintf("[FAIL] %s: %s\n", item.name, item.detail))
		}
	}
	if failed > 0 {
		message := fmt.Sprintf("Schema Registry pre-check failed: %d check(s) failed", failed)
		return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "FAILED", LogOutput: logs.String(), ErrorMsg: message, FailedReason: message}, nil
	}
	return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: logs.String()}, nil
}

func (e *Engine) verifySchemaRegistry(ctx context.Context, t *api.Task) (*api.TaskResult, error) {
	baseURL := strings.TrimRight(strings.TrimSpace(t.Parameters["schema_registry_url"]), "/")
	if baseURL == "" {
		return e.fail(t, "schema_registry_url is required"), nil
	}
	url := baseURL + "/subjects"
	client := &http.Client{Timeout: 10 * time.Second}
	var lastErr error
	for attempt := 1; attempt <= 12; attempt++ {
		req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err != nil {
			return e.fail(t, err.Error()), nil
		}
		resp, err := client.Do(req)
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode >= 200 && resp.StatusCode < 300 {
				return &api.TaskResult{TaskID: t.TaskID, HostID: e.cfg.Agent.HostID, Status: "SUCCESS", LogOutput: "[PASS] Schema Registry REST API responded at " + url}, nil
			}
			lastErr = fmt.Errorf("Schema Registry returned HTTP %d", resp.StatusCode)
		} else {
			lastErr = err
		}
		if attempt < 12 {
			select {
			case <-ctx.Done():
				return e.fail(t, "Schema Registry health check cancelled: "+ctx.Err().Error()), nil
			case <-time.After(5 * time.Second):
			}
		}
	}
	return e.fail(t, "Schema Registry health check failed after 60 seconds: "+lastErr.Error()), nil
}

func endpointAddress(endpoint string) string {
	value := strings.TrimSpace(endpoint)
	if idx := strings.Index(value, "://"); idx >= 0 {
		value = value[idx+3:]
	}
	return value
}

func safeSchemaPath(value string) (string, error) {
	clean := filepath.Clean(strings.TrimSpace(value))
	if clean == "." || clean == "" || !filepath.IsAbs(clean) || clean == string(filepath.Separator) {
		return clean, fmt.Errorf("path must be an absolute non-root path")
	}
	return clean, nil
}

func nearestExistingParent(ctx context.Context, e *Engine, path string) string {
	current := path
	for current != string(filepath.Separator) {
		if _, _, err := e.exec.RunSudo(ctx, "test", "-d", current); err == nil {
			return current
		}
		current = filepath.Dir(current)
	}
	return string(filepath.Separator)
}

func schemaFreeBytes(ctx context.Context, e *Engine, path string) int64 {
	out, _, err := e.exec.Run(ctx, "df", "-Pk", path)
	if err != nil {
		return 0
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) < 2 {
		return 0
	}
	fields := strings.Fields(lines[len(lines)-1])
	if len(fields) < 4 {
		return 0
	}
	kb, _ := strconv.ParseInt(fields[3], 10, 64)
	return kb * 1024
}

func csvValues(raw string) []string {
	result := []string{}
	for _, value := range strings.Split(raw, ",") {
		if trimmed := strings.TrimSpace(value); trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}

func schemaFirstNonBlank(value, fallback string) string {
	if strings.TrimSpace(value) == "" {
		return fallback
	}
	return strings.TrimSpace(value)
}
