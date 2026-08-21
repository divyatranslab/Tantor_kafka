package config

import "testing"

func validConfig() Config {
	var cfg Config
	cfg.Environment = "production"
	cfg.Agent.HostID = "node-1"
	cfg.Agent.ServerURL = "https://control-plane.corp.internal"
	cfg.Agent.CertFile = "/certs/agent.crt"
	cfg.Agent.KeyFile = "/certs/agent.key"
	cfg.Agent.CACert = "/certs/ca.crt"
	cfg.Agent.PollInterval = 10
	return cfg
}

func TestValidateAcceptsExplicitProductionConfiguration(t *testing.T) {
	cfg := validConfig()
	if err := cfg.Validate(); err != nil {
		t.Fatalf("valid configuration rejected: %v", err)
	}
}

func TestValidateRejectsMissingAndMalformedEndpoints(t *testing.T) {
	cfg := validConfig()
	cfg.Agent.ServerURL = ""
	if err := cfg.Validate(); err == nil {
		t.Fatal("missing endpoint must fail")
	}
	cfg.Agent.ServerURL = "not-a-url"
	if err := cfg.Validate(); err == nil {
		t.Fatal("malformed endpoint must fail")
	}
}

func TestValidateRejectsProductionLoopbackAndInvalidPollPortEquivalent(t *testing.T) {
	cfg := validConfig()
	cfg.Agent.ServerURL = "https://localhost:8443"
	if err := cfg.Validate(); err == nil {
		t.Fatal("production loopback endpoint must fail")
	}
	cfg = validConfig()
	cfg.Agent.PollInterval = 70000
	if err := cfg.Validate(); err == nil {
		t.Fatal("invalid poll interval must fail")
	}
}

func TestValidateRejectsCredentialQueryAndPlaceholderURLs(t *testing.T) {
	for _, endpoint := range []string{
		"https://user:password@control.example.net",
		"https://control.example.net/api?token=secret",
		"https://control.example",
	} {
		cfg := validConfig()
		cfg.Agent.ServerURL = endpoint
		if err := cfg.Validate(); err == nil {
			t.Fatalf("unsafe endpoint accepted: %s", endpoint)
		}
	}
	cfg := validConfig()
	cfg.Agent.ServerURL = "https://control.example.net/api"
	if got := cfg.SafeServerEndpoint(); got != "https://control.example.net/api" {
		t.Fatalf("unsafe diagnostic summary: %s", got)
	}
}
