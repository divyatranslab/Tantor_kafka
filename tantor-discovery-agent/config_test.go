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

func validDiscoveryConfiguration() DiscoveryConfig {
	return DiscoveryConfig{
		Environment: "production", HostID: "discovery-1", AgentName: "discovery-node",
		ServerURL: "https://control-plane.corp.internal", MetricsURL: "http://localhost:7071/metrics",
		TLSCACert: "/certs/ca.crt", TLSClientCert: "/certs/agent.crt", TLSClientKey: "/certs/agent.key",
	}
}

func TestValidateRejectsMissingMalformedAndProductionLoopbackEndpoint(t *testing.T) {
	cfg := validDiscoveryConfiguration()
	cfg.ServerURL = ""
	if err := cfg.Validate(); err == nil {
		t.Fatal("missing endpoint must fail")
	}
	cfg = validDiscoveryConfiguration()
	cfg.ServerURL = "invalid"
	if err := cfg.Validate(); err == nil {
		t.Fatal("malformed endpoint must fail")
	}
	cfg = validDiscoveryConfiguration()
	cfg.ServerURL = "https://127.0.0.1:8443"
	if err := cfg.Validate(); err == nil {
		t.Fatal("production loopback endpoint must fail")
	}
}

func TestValidateAcceptsExplicitRuntimeConfiguration(t *testing.T) {
	cfg := validDiscoveryConfiguration()
	if err := cfg.Validate(); err != nil {
		t.Fatalf("valid configuration rejected: %v", err)
	}
}

func TestValidateRejectsCredentialQueryAndPlaceholderURLs(t *testing.T) {
	for _, endpoint := range []string{
		"https://user:password@control.example.net",
		"https://control.example.net/api?token=secret",
		"https://control.invalid",
	} {
		cfg := validDiscoveryConfiguration()
		cfg.ServerURL = endpoint
		if err := cfg.Validate(); err == nil {
			t.Fatalf("unsafe endpoint accepted: %s", endpoint)
		}
	}
	cfg := validDiscoveryConfiguration()
	cfg.ServerURL = "https://control.example.net/api"
	if got := cfg.SafeServerEndpoint(); got != "https://control.example.net/api" {
		t.Fatalf("unsafe diagnostic summary: %s", got)
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
