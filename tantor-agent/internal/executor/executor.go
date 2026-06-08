package executor

import (
	"bytes"
	"context"
	"fmt"
	"os/exec"
	"strings"
	"time"
)

// Executor defines an interface for running commands
type Executor interface {
	RunSudo(ctx context.Context, cmd string, args ...string) (string, string, error)
	Run(ctx context.Context, cmd string, args ...string) (string, string, error)
}

type DefaultExecutor struct{}

func New() *DefaultExecutor {
	return &DefaultExecutor{}
}

// Run executes a command without sudo
func (e *DefaultExecutor) Run(ctx context.Context, name string, args ...string) (string, string, error) {
	return e.execute(ctx, false, name, args...)
}

// RunSudo executes a command with sudo, assuming passwordless sudo is configured
func (e *DefaultExecutor) RunSudo(ctx context.Context, name string, args ...string) (string, string, error) {
	return e.execute(ctx, true, name, args...)
}

func (e *DefaultExecutor) execute(ctx context.Context, useSudo bool, name string, args ...string) (string, string, error) {
	var cmdArgs []string
	if useSudo {
		cmdArgs = append(cmdArgs, "-n", name)
		cmdArgs = append(cmdArgs, args...)
		name = "sudo"
	} else {
		cmdArgs = args
	}

	cmd := exec.CommandContext(ctx, name, cmdArgs...)
	
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr

	err := cmd.Run()
	
	outStr := strings.TrimSpace(stdout.String())
	errStr := strings.TrimSpace(stderr.String())
	
	if err != nil {
		return outStr, errStr, fmt.Errorf("command execution failed: %w", err)
	}

	return outStr, errStr, nil
}

// RunWithTimeout executes a command with a specified timeout
func RunWithTimeout(timeout time.Duration, useSudo bool, name string, args ...string) (string, string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	
	e := New()
	if useSudo {
		return e.RunSudo(ctx, name, args...)
	}
	return e.Run(ctx, name, args...)
}
