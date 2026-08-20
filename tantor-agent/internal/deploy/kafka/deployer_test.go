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

func TestJmxDefaultsAreRoleAware(t *testing.T) {
	tests := []struct {
		role string
		port string
		file string
	}{
		{role: "broker", port: "7071", file: "broker.yml"},
		{role: "controller", port: "7072", file: "controller.yml"},
		{role: "broker_controller", port: "7071", file: "broker-controller.yml"},
	}

	for _, tc := range tests {
		t.Run(tc.role, func(t *testing.T) {
			if got := defaultJmxMetricsPort(tc.role); got != tc.port {
				t.Fatalf("defaultJmxMetricsPort(%q) = %q, want %q", tc.role, got, tc.port)
			}
			if got := filepath.Base(jmxConfigPathForRole("/opt/kafka", tc.role)); got != tc.file {
				t.Fatalf("jmx config for %q = %q, want %q", tc.role, got, tc.file)
			}
		})
	}
}

func TestJmxRequiredForBrokerAndControllerUnlessDisabled(t *testing.T) {
	for _, role := range []string{"broker", "controller", "broker_controller"} {
		_, isBroker, isController := normalizeKRaftRole(role)
		task := &api.Task{Parameters: map[string]string{"role": role}}
		if !jmxRequiredForTask(task, isBroker, isController) {
			t.Fatalf("expected JMX to be required for role %q", role)
		}
		task.Parameters["jmx_enabled"] = "false"
		if jmxRequiredForTask(task, isBroker, isController) {
			t.Fatalf("expected explicit jmx_enabled=false to disable JMX for role %q", role)
		}
	}
}

func TestMergeCustomKafkaPropertiesRemovesConflictingQuorumKeys(t *testing.T) {
	base := "process.roles=controller\ncontroller.quorum.voters=1@old:9093\nnum.io.threads=8\n"
	merged := mergeCustomKafkaProperties(base, map[string]string{
		"process.roles":                       "controller",
		"controller.quorum.voters":            "",
		"controller.quorum.bootstrap.servers": "10.0.0.1:9093,10.0.0.2:9093",
	})

	if strings.Contains(merged, "controller.quorum.voters=") {
		t.Fatalf("dynamic config retained static voter property:\n%s", merged)
	}
	if !strings.Contains(merged, "controller.quorum.bootstrap.servers=10.0.0.1:9093,10.0.0.2:9093") {
		t.Fatalf("dynamic bootstrap servers missing:\n%s", merged)
	}
	if !strings.Contains(merged, "num.io.threads=8") {
		t.Fatalf("unmanaged custom property was removed:\n%s", merged)
	}
}

func TestUsesFlatKafkaConfigLayout(t *testing.T) {
	if usesFlatKafkaConfigLayout("3.9.2") {
		t.Fatal("Kafka 3.9 must retain the config/kraft layout")
	}
	if !usesFlatKafkaConfigLayout("4.0.0") {
		t.Fatal("Kafka 4 must use the flat config layout")
	}
}

func TestValidateMetaPropertiesRequiresMatchingIdentity(t *testing.T) {
	tmpDir := t.TempDir()
	path := filepath.Join(tmpDir, "meta.properties")
	if err := os.WriteFile(path, []byte("cluster.id=cluster-identity-12345\nnode.id=101\n"), 0600); err != nil {
		t.Fatalf("write meta.properties: %v", err)
	}
	dirs := []string{tmpDir}
	if err := validateMetaProperties(context.Background(), nil, dirs, "cluster-identity-12345", "101"); err != nil {
		t.Fatalf("matching identity rejected: %v", err)
	}
	if err := validateMetaProperties(context.Background(), nil, dirs, "different-cluster", "101"); err == nil {
		t.Fatal("cluster identity mismatch was accepted")
	}
	if err := validateMetaProperties(context.Background(), nil, dirs, "cluster-identity-12345", "102"); err == nil {
		t.Fatal("node identity mismatch was accepted")
	}
}
