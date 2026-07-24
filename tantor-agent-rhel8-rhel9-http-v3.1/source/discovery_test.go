package main

import (
	"log/slog"
	"os"
	"path/filepath"
	"testing"
)

func TestExtractBootstrapReplacesLoopbackWithNodeAddress(t *testing.T) {
	got := extractBootstrapServersWithFallback("PLAINTEXT://localhost:9092,CONTROLLER://localhost:9093", "192.168.3.161")
	if got != "192.168.3.161:9092" {
		t.Fatalf("got %q", got)
	}
}

func TestDetectSecurityUsesBrokerListenerOnly(t *testing.T) {
	got := detectSecurityForBrokerListeners(
		"PLAINTEXT://192.168.3.161:9092",
		"PLAINTEXT://192.168.3.161:9092,CONTROLLER://192.168.3.161:9093",
		"CONTROLLER:SASL_SSL,PLAINTEXT:PLAINTEXT",
	)
	if got != "PLAINTEXT" {
		t.Fatalf("got %q want PLAINTEXT", got)
	}
}

func TestControllerConfigIsRetainedAndEnrichedFromBroker(t *testing.T) {
	root := t.TempDir()
	configDir := filepath.Join(root, "config")
	libsDir := filepath.Join(root, "libs")
	brokerData := filepath.Join(root, "broker-data")
	controllerData := filepath.Join(root, "controller-data")
	if err := os.MkdirAll(configDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(libsDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(brokerData, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(controllerData, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(libsDir, "kafka-clients-4.1.0.jar"), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	meta := []byte("cluster.id=XTjU8A84S6uUdNOSS126gQ\n")
	if err := os.WriteFile(filepath.Join(brokerData, "meta.properties"), meta, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(controllerData, "meta.properties"), meta, 0o644); err != nil {
		t.Fatal(err)
	}

	brokerCfg := filepath.Join(configDir, "broker.properties")
	controllerCfg := filepath.Join(configDir, "controller.properties")
	if err := os.WriteFile(brokerCfg, []byte(
		"process.roles=broker\n"+
			"node.id=1\n"+
			"listeners=PLAINTEXT://192.168.3.161:9092\n"+
			"advertised.listeners=PLAINTEXT://192.168.3.161:9092\n"+
			"log.dirs="+brokerData+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(controllerCfg, []byte(
		"process.roles=controller\n"+
			"node.id=101\n"+
			"listeners=CONTROLLER://192.168.3.161:9093\n"+
			"metadata.log.dir="+controllerData+"\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	broker := parseServerProperties(brokerCfg, true, "192.168.3.161", "prod", "production-kafka")
	controller := parseServerProperties(controllerCfg, true, "192.168.3.161", "prod", "production-kafka")
	if broker == nil || controller == nil {
		t.Fatal("expected both broker and controller records")
	}
	broker.KafkaVersion = detectVersion(broker.InstallPath)
	controller.KafkaVersion = detectVersion(controller.InstallPath)

	got := enrichClusterRecords([]DiscoveredCluster{*broker, *controller}, slog.Default())
	if len(got) != 2 {
		t.Fatalf("got %d records want 2", len(got))
	}
	for _, record := range got {
		if record.BootstrapServers != "192.168.3.161:9092" {
			t.Fatalf("node %d bootstrap %q", record.NodeID, record.BootstrapServers)
		}
		if record.KafkaClusterID != "XTjU8A84S6uUdNOSS126gQ" {
			t.Fatalf("node %d cluster id %q", record.NodeID, record.KafkaClusterID)
		}
		if !record.IsRunning {
			t.Fatalf("node %d expected running", record.NodeID)
		}
	}
}
