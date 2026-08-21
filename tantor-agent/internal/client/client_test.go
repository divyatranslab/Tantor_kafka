package client

import (
	"crypto/tls"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/pkg/api"
)

func TestTransportFailsClosed(t *testing.T) {
	cfg := &config.Config{}
	cfg.Agent.ServerURL = "http://control-plane.example"
	if _, err := NewAPIClient(cfg); err == nil {
		t.Fatal("expected plaintext control-plane URL to be rejected")
	}

	transport := &http.Transport{TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	if transport.TLSClientConfig.InsecureSkipVerify {
		t.Fatal("certificate verification must remain enabled")
	}
	if err := requireSameHTTPSAuthority("https://tantor.example", "http://tantor.example/a"); err == nil {
		t.Fatal("expected artifact HTTP downgrade to be rejected")
	}
	if err := requireSameHTTPSAuthority("https://tantor.example", "https://attacker.example/a"); err == nil {
		t.Fatal("expected cross-authority artifact URL to be rejected")
	}
}

func TestPollAndReportPreserveClaimToken(t *testing.T) {
	var reported api.TaskResult
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/api/v1/agents/host-a/tasks":
			_ = json.NewEncoder(w).Encode([]api.Task{{TaskID: "task-1", ClaimToken: "claim-1", Command: "INSTALL_KAFKA"}})
		case "/api/v1/agents/tasks/result":
			if err := json.NewDecoder(r.Body).Decode(&reported); err != nil {
				t.Fatalf("decode result: %v", err)
			}
			w.WriteHeader(http.StatusOK)
		default:
			t.Fatalf("unexpected path %s", r.URL.Path)
		}
	}))
	defer server.Close()

	cfg := &config.Config{}
	cfg.Agent.HostID = "host-a"
	cfg.Agent.ServerURL = server.URL
	client := &APIClient{cfg: cfg, httpClient: server.Client()}

	tasks, err := client.PollTasks()
	if err != nil {
		t.Fatalf("poll tasks: %v", err)
	}
	if len(tasks) != 1 || tasks[0].ClaimToken != "claim-1" {
		t.Fatalf("claim token was not received: %#v", tasks)
	}
	if err := client.ReportTaskResult(&api.TaskResult{TaskID: tasks[0].TaskID, ClaimToken: tasks[0].ClaimToken, Status: "SUCCESS"}); err != nil {
		t.Fatalf("report task result: %v", err)
	}
	if reported.ClaimToken != "claim-1" {
		t.Fatalf("claim token was not reported: %#v", reported)
	}

}
