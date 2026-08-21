package main

import "testing"

func TestRequireConfigPathRejectsImplicitExample(t *testing.T) {
	if err := requireConfigPath(""); err == nil {
		t.Fatal("starting without -config must fail")
	}
	if err := requireConfigPath("/etc/tantor-discovery-agent/config.yaml"); err != nil {
		t.Fatalf("explicit config path rejected: %v", err)
	}
}
