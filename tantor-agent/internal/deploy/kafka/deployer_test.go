package kafka

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"io.translab/tantor-agent/pkg/api"
)

func TestGenerateZooKeeperBrokerConfig(t *testing.T) {
	installDir := t.TempDir()
	dataDir := t.TempDir()
	task := &api.Task{Parameters: map[string]string{
		"mode":                "zookeeper",
		"service_role":        "broker",
		"node_id":             "2",
		"config_file":         "server.properties",
		"zookeeper_connect":   "10.0.0.1:2181,10.0.0.2:2181",
		"replication_factor":  "2",
		"min_insync_replicas": "1",
		"num_partitions":      "3",
	}}

	d := &Deployer{}
	if err := d.generateConfigs(context.Background(), task, installDir, dataDir); err != nil {
		t.Fatalf("generateConfigs returned error: %v", err)
	}

	content, err := os.ReadFile(filepath.Join(installDir, "config", "server.properties"))
	if err != nil {
		t.Fatalf("read broker config: %v", err)
	}
	config := string(content)
	for _, expected := range []string{
		"broker.id=2",
		"zookeeper.connect=10.0.0.1:2181,10.0.0.2:2181",
		"default.replication.factor=2",
		"min.insync.replicas=1",
	} {
		if !strings.Contains(config, expected) {
			t.Errorf("broker config missing %q\n%s", expected, config)
		}
	}
	if strings.Contains(config, "process.roles=") {
		t.Errorf("ZooKeeper broker config contains KRaft process.roles\n%s", config)
	}
}

func TestGenerateZooKeeperServerConfigAndMyID(t *testing.T) {
	installDir := t.TempDir()
	dataDir := t.TempDir()
	task := &api.Task{Parameters: map[string]string{
		"mode":              "zookeeper",
		"service_role":      "zookeeper",
		"node_id":           "1002",
		"config_file":       "zookeeper.properties",
		"kafka_data_dir":    dataDir,
		"zookeeper_port":    "2181",
		"zookeeper_servers": "server.1001=10.0.0.1:2888:3888\nserver.1002=10.0.0.2:2888:3888",
	}}

	d := &Deployer{}
	if err := d.generateConfigs(context.Background(), task, installDir, dataDir); err != nil {
		t.Fatalf("generateConfigs returned error: %v", err)
	}

	configPath := filepath.Join(installDir, "config", "zookeeper.properties")
	content, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatalf("read ZooKeeper config: %v", err)
	}
	if !strings.Contains(string(content), "server.1002=10.0.0.2:2888:3888") {
		t.Fatalf("ZooKeeper ensemble members missing from config:\n%s", content)
	}

	myID, err := os.ReadFile(filepath.Join(dataDir, "zookeeper-data", "myid"))
	if err != nil {
		t.Fatalf("read myid: %v", err)
	}
	if strings.TrimSpace(string(myID)) != "1002" {
		t.Fatalf("unexpected myid %q", myID)
	}
}

func TestDeploymentModeDefaultsToKRaft(t *testing.T) {
	if got := deploymentModeForTask(&api.Task{Parameters: map[string]string{}}); got != "kraft" {
		t.Fatalf("expected kraft, got %q", got)
	}
}
