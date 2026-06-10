package kafka

const SystemdTemplate = `[Unit]
Description=Apache Kafka Server
Documentation=http://kafka.apache.org/documentation.html
Requires=network.target
After=network.target

[Service]
Type=simple
User={{.User}}
Group={{.Group}}
Environment="JAVA_HOME={{.JavaHome}}"
Environment="KAFKA_HEAP_OPTS=-Xmx{{.HeapSize}} -Xms{{.HeapSize}}"
{{if ne .JmxPort ""}}Environment="JMX_PORT={{.JmxPort}}"{{end}}
Environment="KAFKA_OPTS=-javaagent:{{.InstallDir}}/jmx/jmx_prometheus_javaagent.jar=7071:{{.InstallDir}}/jmx/jmx_config.yml"
ExecStart={{.InstallDir}}/bin/kafka-server-start.sh {{.InstallDir}}/config/kraft/server.properties
ExecStop={{.InstallDir}}/bin/kafka-server-stop.sh
Restart=on-failure
LimitNOFILE=100000

[Install]
WantedBy=multi-user.target
`

const JmxConfigTemplate = `rules:
  - pattern: "kafka.server<type=(.+), name=(.+)><>(\\w+)"
    name: "kafka_server_$1_$2_$3"
  - pattern: "kafka.network<type=(.+), name=(.+)><>(\\w+)"
    name: "kafka_network_$1_$2_$3"
  - pattern: "kafka.controller<type=(.+), name=(.+)><>(\\w+)"
    name: "kafka_controller_$1_$2_$3"
  - pattern: "kafka.log<type=(.+), name=(.+)><>(\\w+)"
    name: "kafka_log_$1_$2_$3"
  - pattern: "java.lang<type=(.+)><>(\\w+)"
    name: "jvm_$1_$2"
`

const ServerPropertiesTemplate = `
# KRaft Node Config
process.roles={{.Role}}
node.id={{.NodeId}}
controller.quorum.voters={{.QuorumVoters}}

# Listeners
listeners=PLAINTEXT://{{.Hostname}}:{{.ListenerPort}},CONTROLLER://{{.Hostname}}:{{.ControllerPort}}
inter.broker.listener.name=PLAINTEXT
advertised.listeners=PLAINTEXT://{{.Hostname}}:{{.ListenerPort}}
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL

# Log Directories
log.dirs={{.LogDirs}}

# Internal Topic Settings
num.partitions={{.NumPartitions}}
offsets.topic.replication.factor={{.RepFactor}}
transaction.state.log.replication.factor={{.RepFactor}}
transaction.state.log.min.isr=1
`
