package kafka

import (
	"context"
	"encoding/json"
	"io.translab/tantor-agent/internal/client"
	"io.translab/tantor-agent/internal/config"
	"io.translab/tantor-agent/internal/executor"
	"io.translab/tantor-agent/pkg/api"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"
	"time"
)

func TestPortCheckUsesSelectedAddress(t *testing.T) {
	l, err := net.Listen("tcp", "127.0.0.2:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	host, port, _ := net.SplitHostPort(l.Addr().String())
	d := &Deployer{}
	if err := d.waitForListeningPort(context.Background(), host, port, time.Second); err != nil {
		t.Fatal(err)
	}
}
func TestInstallDeleteShareDatabaseClusterState(t *testing.T) {
	cfg := &config.Config{}
	cfg.Paths.DataDir = t.TempDir()
	d := &Deployer{cfg: cfg}
	install := &api.Task{Parameters: map[string]string{"db_cluster_id": "db-id", "cluster_id": "kraft-id"}}
	cleanup := &api.Task{Parameters: map[string]string{"cluster_id": "db-id"}}
	if d.statePath(install) != d.statePath(cleanup) {
		t.Fatal("install/delete identity mismatch")
	}
	state := deploymentState{ActiveDir: "/opt/kafka", VersionedDir: "/opt/kafka-4.1", DataDir: "/data/kafka", Services: []string{"kafka"}}
	if err := d.saveDeploymentState(install, state); err != nil {
		t.Fatal(err)
	}
	if _, err := d.loadDeploymentState(cleanup); err != nil {
		t.Fatal(err)
	}
}
func TestLiveKafkaHealth(t *testing.T) {
	file := os.Getenv("TANTOR_TEST_LIVE_TASK")
	if file == "" {
		t.Skip("opt-in read-only live validation")
	}
	data, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	var task api.Task
	if err = json.Unmarshal(data, &task); err != nil {
		t.Fatal(err)
	}
	// Capture status reports locally; do not change the management-server task.
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(204) }))
	defer srv.Close()
	cfg := &config.Config{}
	cfg.Agent.ServerURL = srv.URL
	cfg.Auth.Mode = "none"
	c, err := client.NewAPIClient(cfg)
	if err != nil {
		t.Fatal(err)
	}
	d := NewDeployer(cfg, c, executor.New(executor.Options{PrivilegeMode: "direct"}))
	var logs strings.Builder
	err = d.validateKRaftDeployment(context.Background(), &task, "/opt/kafka", &logs)
	t.Log(logs.String())
	if err != nil {
		t.Fatal(err)
	}
}
