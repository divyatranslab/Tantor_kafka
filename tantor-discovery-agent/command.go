package main

import (
	"context"
	"os"
	"os/exec"
	"time"
)

const commandWaitDelay = 2 * time.Second

func commandOutput(ctx context.Context, timeout time.Duration, name string, args ...string) ([]byte, error) {
	commandCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	command := exec.CommandContext(commandCtx, name, args...)
	command.WaitDelay = commandWaitDelay
	output, err := command.Output()
	if commandCtx.Err() != nil {
		return output, commandCtx.Err()
	}
	return output, err
}

func runCommand(ctx context.Context, timeout time.Duration, name string, args ...string) error {
	commandCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	command := exec.CommandContext(commandCtx, name, args...)
	command.WaitDelay = commandWaitDelay
	err := command.Run()
	if commandCtx.Err() != nil {
		return commandCtx.Err()
	}
	return err
}

func runAttachedCommand(ctx context.Context, timeout time.Duration, name string, args ...string) error {
	commandCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	command := exec.CommandContext(commandCtx, name, args...)
	command.WaitDelay = commandWaitDelay
	command.Stdout = os.Stdout
	command.Stderr = os.Stderr
	err := command.Run()
	if commandCtx.Err() != nil {
		return commandCtx.Err()
	}
	return err
}
