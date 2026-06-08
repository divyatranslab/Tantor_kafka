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
Environment="KAFKA_HEAP_OPTS=-Xmx1G -Xms1G"
ExecStart={{.InstallDir}}/bin/kafka-server-start.sh {{.InstallDir}}/config/kraft/server.properties
ExecStop={{.InstallDir}}/bin/kafka-server-stop.sh
Restart=on-failure
LimitNOFILE=100000

[Install]
WantedBy=multi-user.target
`

const ServerPropertiesTemplate = `
# KRaft Node Config
process.roles=broker,controller
node.id={{.NodeId}}
controller.quorum.voters={{.QuorumVoters}}

# Listeners
listeners=PLAINTEXT://{{.Hostname}}:9092,CONTROLLER://{{.Hostname}}:9093
inter.broker.listener.name=PLAINTEXT
advertised.listeners=PLAINTEXT://{{.Hostname}}:9092
controller.listener.names=CONTROLLER
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT,SSL:SSL,SASL_PLAINTEXT:SASL_PLAINTEXT,SASL_SSL:SASL_SSL

# Log Directories
log.dirs={{.DataDir}}/kafka-logs

# Internal Topic Settings
num.partitions=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
`
