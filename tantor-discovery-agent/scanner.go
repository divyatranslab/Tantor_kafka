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

type ProcessInfo struct {
	Cmdline     string
	SystemdUnit string
	Cwd         string
	Pid         string
}

func extractPropsPath(cmdline, cwd string) string {
	// Look for any .properties file in the cmdline
	fields := strings.Fields(cmdline)
	for _, f := range fields {
		if strings.HasSuffix(f, ".properties") {
			// It might be like -D... but usually properties is a standalone arg
			// Wait, let's strip any leading prefix if it's not a standalone arg
			// But for Kafka it is usually a standalone arg: bin/kafka-server-start.sh config/server.properties
			if !filepath.IsAbs(f) && cwd != "" {
				abs, err := filepath.Abs(filepath.Join(cwd, f))
				if err == nil {
					return abs
				}
			}
			abs, err := filepath.Abs(f)
			if err == nil {
				return abs
			}
			return f
		}
	}
	return ""
}

func getRunningKafkaPropsFiles() []ProcessInfo {
	var result []ProcessInfo

	// 1. Try to find running processes via pgrep java
	out, _ := exec.Command("pgrep", "java").Output()
	if len(strings.TrimSpace(string(out))) > 0 {
		for _, pid := range strings.Split(strings.TrimSpace(string(out)), "\n") {
			pid = strings.TrimSpace(pid)
			if pid == "" {
				continue
			}
			cmdline := readProcessCmdline(pid)
			cwd := readProcessCwd(pid)
			if cmdline == "" || (!strings.Contains(strings.ToLower(cmdline), "kafka") && !strings.Contains(strings.ToLower(cmdline), ".properties")) {
				continue
			}
			result = append(result, ProcessInfo{Cmdline: cmdline, Cwd: cwd, Pid: pid})
		}
	}

	// 2. Try to find running processes via systemd
	systemdProps := getSystemdRunningKafkaPropsFiles()
	result = append(result, systemdProps...)

	return result
}

func readProcessCwd(pid string) string {
	cwd, err := os.Readlink(fmt.Sprintf("/proc/%s/cwd", pid))
	if err == nil {
		return cwd
	}
	return ""
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

func getSystemdRunningKafkaPropsFiles() []ProcessInfo {
	var result []ProcessInfo
	if err := exec.Command("systemctl", "--version").Run(); err != nil {
		return result
	}

	out1, _ := exec.Command("systemctl", "list-units", "--type=service", "--state=running", "--no-legend", "--plain").Output()
	
	lines := strings.Split(string(out1), "\n")
	for _, line := range lines {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		unit := fields[0]

		execStartOut, _ := exec.Command("systemctl", "show", unit, "--property=ExecStart", "--value").Output()
		execStartStr := string(execStartOut)
		cwdOut, _ := exec.Command("systemctl", "show", unit, "--property=WorkingDirectory", "--value").Output()
		cwdStr := strings.TrimSpace(string(cwdOut))
		
		if !strings.Contains(strings.ToLower(execStartStr), "kafka") && !strings.Contains(strings.ToLower(execStartStr), ".properties") {
			continue
		}

		result = append(result, ProcessInfo{Cmdline: execStartStr, SystemdUnit: unit, Cwd: cwdStr})
	}
	return result
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
