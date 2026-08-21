package main

import (
	"strings"
	"testing"

	"gopkg.in/yaml.v3"
)

func TestDiscoveryTransportRejectsInsecureConfiguration(t *testing.T) {
	cfg := DiscoveryConfig{ServerURL: "http://control-plane.example"}
	if err := cfg.ValidateTransport(); err == nil {
		t.Fatal("expected plaintext control-plane URL to be rejected")
	}
	cfg.ServerURL = "https://control-plane.example"
	cfg.TLSCACert = "/ca.pem"
	cfg.TLSClientCert = "/agent.pem"
	cfg.TLSClientKey = "/agent.key"
	if err := cfg.ValidateTransport(); err != nil {
		t.Fatalf("expected complete mTLS configuration to validate: %v", err)
	}
}

func TestDeprecatedInsecureOverrideIsRejectedAsUnknown(t *testing.T) {
	decoder := yaml.NewDecoder(strings.NewReader("discovery:\n  tls_insecure_skip_verify: true\n"))
	decoder.KnownFields(true)
	var cfg Config
	if err := decoder.Decode(&cfg); err == nil {
		t.Fatal("expected removed insecure TLS option to be rejected")
	}
}
