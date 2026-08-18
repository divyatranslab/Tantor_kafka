package main

import (
	"context"
	"errors"
	"os"
	"testing"
	"time"
)

func TestCommandHelperProcess(t *testing.T) {
	if os.Getenv("TANTOR_COMMAND_HELPER") != "1" {
		return
	}
	time.Sleep(5 * time.Second)
	os.Exit(0)
}

func TestCommandOutputHonorsTimeout(t *testing.T) {
	t.Setenv("TANTOR_COMMAND_HELPER", "1")
	started := time.Now()
	_, err := commandOutput(context.Background(), 50*time.Millisecond, os.Args[0], "-test.run=TestCommandHelperProcess")
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected command deadline, got %v", err)
	}
	if elapsed := time.Since(started); elapsed > commandWaitDelay+time.Second {
		t.Fatalf("command timeout was not enforced; elapsed %s", elapsed)
	}
}
