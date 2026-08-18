package main

import (
	"os"
	"testing"
	"time"

	"gopkg.in/yaml.v3"
)

func TestEffectiveHTTPSettingsDefaults(t *testing.T) {
	settings, err := (DiscoveryConfig{}).EffectiveHTTPSettings()
	if err != nil {
		t.Fatalf("defaults must be valid: %v", err)
	}
	if settings.RequestTimeout != 10*time.Second || settings.RetryMaxAttempts != 3 || settings.CircuitFailureThreshold != 5 {
		t.Fatalf("unexpected defaults: %+v", settings)
	}
}

func TestSampleConfigurationIsValid(t *testing.T) {
	content, err := os.ReadFile("configs/discovery.yaml")
	if err != nil {
		t.Fatalf("read sample configuration: %v", err)
	}
	var config Config
	if err := yaml.Unmarshal(content, &config); err != nil {
		t.Fatalf("parse sample configuration: %v", err)
	}
	if _, err := config.Discovery.EffectiveHTTPSettings(); err != nil {
		t.Fatalf("sample HTTP configuration is invalid: %v", err)
	}
	if _, err := config.Discovery.EffectiveCommandTimeout(); err != nil {
		t.Fatalf("sample command configuration is invalid: %v", err)
	}
}

func TestEffectiveHTTPSettingsRejectsInvalidValues(t *testing.T) {
	config := DiscoveryConfig{HTTP: HTTPConfig{RequestTimeout: "0s"}}
	if _, err := config.EffectiveHTTPSettings(); err == nil {
		t.Fatal("expected zero request timeout to be rejected")
	}

	config = DiscoveryConfig{HTTP: HTTPConfig{RetryMaxAttempts: 11}}
	if _, err := config.EffectiveHTTPSettings(); err == nil {
		t.Fatal("expected excessive retry attempts to be rejected")
	}
}
