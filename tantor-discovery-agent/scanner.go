package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// =========================================================================
// Running-process detection
// =========================================================================

func getRunningKafkaPropsFiles() map[string]bool {
	result := make(map[string]bool)

	out, err := exec.Command("pgrep", "-f", "kafka.Kafka").Output()
	if err != nil {
		// also try kafka.server.KafkaServer
		out, err = exec.Command("pgrep", "-f", "kafka.server.KafkaServer").Output()
	}
	if err != nil || len(strings.TrimSpace(string(out))) == 0 {
		return result
	}

	for _, pid := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		pid = strings.TrimSpace(pid)
		if pid == "" {
			continue
		}
		// Read /proc/<pid>/cmdline for the full command line
		cmdline := readProcessCmdline(pid)
		if cmdline == "" {
			continue
		}
		for _, token := range strings.Fields(cmdline) {
			if strings.HasSuffix(token, ".properties") {
				// Resolve to absolute path
				abs, err := filepath.Abs(token)
				if err == nil {
					result[abs] = true
				} else {
					result[token] = true
				}
			}
		}
	}
	return result
}

func readProcessCmdline(pid string) string {
	// Try /proc/<pid>/cmdline first (Linux)
	data, err := os.ReadFile(fmt.Sprintf("/proc/%s/cmdline", pid))
	if err == nil {
		// cmdline uses NUL separators
		return strings.ReplaceAll(string(data), "\x00", " ")
	}
	// Fallback to ps
	out, err := exec.Command("ps", "-p", pid, "-o", "args=").Output()
	if err == nil {
		return string(out)
	}
	return ""
}

// =========================================================================
// Filesystem scanning
// =========================================================================

func findAllConfigProperties(scanPaths []string) []string {
	var results []string
	seen := map[string]bool{}

	for _, base := range scanPaths {
		info, err := os.Stat(base)
		if err != nil || !info.IsDir() {
			continue
		}

		_ = filepath.Walk(base, func(path string, fi os.FileInfo, err error) error {
			if err != nil {
				return nil
			}
			// Skip very deep directories (safety)
			rel, _ := filepath.Rel(base, path)
			if strings.Count(rel, string(os.PathSeparator)) > 8 {
				return filepath.SkipDir
			}
			// Skip certain directories to avoid infinite loops and permission issues
			if fi.IsDir() {
				name := fi.Name()
				if name == ".git" || name == "node_modules" || name == "__pycache__" {
					return filepath.SkipDir
				}
				// Skip OS level directories if we are scanning from root
				if base == "/" && (path == "/proc" || path == "/sys" || path == "/dev" || path == "/run" || path == "/boot" || path == "/tmp" || path == "/etc" || path == "/lib" || path == "/lib64" || path == "/usr/lib") {
					return filepath.SkipDir
				}
				return nil
			}
			if fi.Name() == "server.properties" || fi.Name() == "broker.properties" {
				abs, _ := filepath.Abs(path)
				if !seen[abs] {
					seen[abs] = true
					results = append(results, abs)
				}
			}
			return nil
		})
	}
	return results
}
