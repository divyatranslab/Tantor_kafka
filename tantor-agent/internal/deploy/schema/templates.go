package schema

const SystemdTemplate = `[Unit]
Description=Tantor Schema Registry
Documentation=http://docs.confluent.io/
Wants=network-online.target
After=network-online.target kafka.service broker.service controller.service

[Service]
Type=simple
User={{.User}}
Group={{.Group}}
Environment="JAVA_HOME={{.JavaHome}}"
Environment="LOG_DIR={{.LogDir}}"
Environment="SCHEMA_REGISTRY_HEAP_OPTS=-Xms{{.HeapSize}} -Xmx{{.HeapSize}}"
WorkingDirectory={{.WorkingDir}}
ExecStart={{.InstallDir}}/bin/schema-registry-start {{.ConfigDir}}/schema-registry.properties
ExecStop={{.InstallDir}}/bin/schema-registry-stop
Restart=on-failure
RestartSec=5
LimitNOFILE=100000
TimeoutStopSec=180

[Install]
WantedBy=multi-user.target
`

const SchemaRegistryPropertiesTemplate = `
# Managed by Tantor
listeners=http://0.0.0.0:{{.RestPort}}
host.name={{.HostName}}
kafkastore.bootstrap.servers={{.BootstrapServers}}
kafkastore.topic={{.KafkaStoreTopic}}
kafkastore.topic.replication.factor={{.ReplicationFactor}}
schema.registry.group.id={{.GroupID}}
compatibility.level={{.CompatibilityLevel}}
debug=false
`
