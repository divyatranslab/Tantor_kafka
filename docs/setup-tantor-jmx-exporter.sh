#!/usr/bin/env bash
# Tantor Kafka JMX Prometheus exporter setup.
# Installs systemd drop-ins for Kafka broker/controller services without editing
# the original unit files. Broker metrics default to port 7071 because Tantor
# backend health checks http://<broker-host>:7071/metrics.

set -euo pipefail

RUNTIME_USER="apb_app"
RUNTIME_GROUP="apb_app"
PROM_DIR="/opt/tantor/jmx-prometheus"
JMX_JAR_PATH=""
BROKER_SERVICE="broker"
CONTROLLER_SERVICE="controller"
BROKER_METRICS_PORT="7071"
CONTROLLER_METRICS_PORT="7072"
ENABLE_CONTROLLER="true"
RESTART_SERVICES="true"
LOG_FILE="/var/log/tantor-jmx-exporter-setup.log"

usage() {
  cat <<EOF
Usage: sudo $0 --jmx-jar /path/jmx_prometheus_javaagent.jar [options]

Required:
  --jmx-jar PATH                 Existing JMX exporter javaagent jar.

Options:
  --runtime-user USER            Kafka runtime user. Default: ${RUNTIME_USER}
  --runtime-group GROUP          Kafka runtime group. Default: ${RUNTIME_GROUP}
  --prom-dir DIR                 Config/env directory. Default: ${PROM_DIR}
  --broker-service NAME          Broker systemd service. Default: ${BROKER_SERVICE}
  --controller-service NAME      Controller systemd service. Default: ${CONTROLLER_SERVICE}
  --broker-port PORT             Broker metrics port. Default: ${BROKER_METRICS_PORT}
  --controller-port PORT         Controller metrics port. Default: ${CONTROLLER_METRICS_PORT}
  --disable-controller           Do not configure controller exporter.
  --no-restart                   Write config only; do not restart services.
  -h, --help                     Show this help.

Example:
  sudo $0 \\
    --runtime-user apb_app \\
    --runtime-group apb_app \\
    --jmx-jar /opt/tantor/jmx-prometheus/jmx_prometheus_javaagent.jar
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --runtime-user) RUNTIME_USER="${2:?}"; shift 2 ;;
    --runtime-group) RUNTIME_GROUP="${2:?}"; shift 2 ;;
    --prom-dir) PROM_DIR="${2:?}"; shift 2 ;;
    --jmx-jar) JMX_JAR_PATH="${2:?}"; shift 2 ;;
    --broker-service) BROKER_SERVICE="${2:?}"; shift 2 ;;
    --controller-service) CONTROLLER_SERVICE="${2:?}"; shift 2 ;;
    --broker-port) BROKER_METRICS_PORT="${2:?}"; shift 2 ;;
    --controller-port) CONTROLLER_METRICS_PORT="${2:?}"; shift 2 ;;
    --disable-controller) ENABLE_CONTROLLER="false"; shift ;;
    --no-restart) RESTART_SERVICES="false"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [INFO] $*" | tee -a "$LOG_FILE"; }
warn() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [WARN] $*" | tee -a "$LOG_FILE" >&2; }
die() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] [ERROR] $*" | tee -a "$LOG_FILE" >&2; exit 1; }

require_root() {
  [[ "${EUID}" -eq 0 ]] || die "Run as root because systemd drop-ins and service restarts require privilege."
}

validate_port() {
  local name="$1"
  local port="$2"
  [[ "$port" =~ ^[0-9]+$ ]] || die "${name} must be numeric: ${port}"
  (( port >= 1024 && port <= 65535 )) || die "${name} must be between 1024 and 65535: ${port}"
}

shell_quote() {
  printf "'%s'" "$(printf "%s" "$1" | sed "s/'/'\\\\''/g")"
}

validate_inputs() {
  [[ -n "$JMX_JAR_PATH" ]] || die "--jmx-jar is required."
  [[ -f "$JMX_JAR_PATH" ]] || die "JMX exporter jar not found: ${JMX_JAR_PATH}"
  command -v systemctl >/dev/null 2>&1 || die "systemctl not found."
  command -v curl >/dev/null 2>&1 || die "curl not found."
  id -u "$RUNTIME_USER" >/dev/null 2>&1 || die "Runtime user does not exist: ${RUNTIME_USER}"
  getent group "$RUNTIME_GROUP" >/dev/null 2>&1 || die "Runtime group does not exist: ${RUNTIME_GROUP}"
  validate_port "broker port" "$BROKER_METRICS_PORT"
  validate_port "controller port" "$CONTROLLER_METRICS_PORT"
  if [[ "$ENABLE_CONTROLLER" == "true" && "$BROKER_METRICS_PORT" == "$CONTROLLER_METRICS_PORT" ]]; then
    die "Broker and controller exporter ports must be different on a shared host."
  fi
}

write_config() {
  local config_file="$1"
  cat > "$config_file" <<'EOF'
lowercaseOutputName: true
lowercaseOutputLabelNames: true
rules:
- pattern: 'kafka.server<type=BrokerTopicMetrics, name=(BytesInPerSec|BytesOutPerSec|MessagesInPerSec)><>Count'
  name: kafka_server_brokertopicmetrics_$1_total
  type: COUNTER
- pattern: 'kafka.server<type=BrokerTopicMetrics, name=(BytesInPerSec|BytesOutPerSec|MessagesInPerSec), topic=(.+)><>Count'
  name: kafka_server_brokertopicmetrics_$1_total
  type: COUNTER
  labels:
    topic: "$2"
- pattern: 'kafka.server<type=ReplicaManager, name=(UnderReplicatedPartitions|PartitionCount|LeaderCount)><>Value'
  name: kafka_server_replicamanager_$1
  type: GAUGE
- pattern: 'kafka.controller<type=KafkaController, name=ActiveControllerCount><>Value'
  name: kafka_controller_kafkacontroller_activecontrollercount
  type: GAUGE
- pattern: 'java.lang<type=Memory><HeapMemoryUsage>(used|committed|max)'
  name: jvm_memory_heap_$1
  type: GAUGE
- pattern: 'java.lang<type=OperatingSystem><>ProcessCpuLoad'
  name: process_cpu_load
  type: GAUGE
- pattern: 'java.lang<type=OperatingSystem><>SystemCpuLoad'
  name: system_cpu_load
  type: GAUGE
- pattern: '.*'
EOF
  chown "${RUNTIME_USER}:${RUNTIME_GROUP}" "$config_file"
  chmod 0640 "$config_file"
}

install_dropin() {
  local service="$1"
  local port="$2"
  local label="$3"
  local unit="/etc/systemd/system/${service}.service"
  local dropin_dir="/etc/systemd/system/${service}.service.d"
  local dropin="${dropin_dir}/10-tantor-jmx-exporter.conf"
  local env_file="${PROM_DIR}/${service}-jmx.env"
  local config_file="${PROM_DIR}/${service}-jmx.yml"

  [[ -f "$unit" ]] || { warn "${service}.service not found; skipping ${label}."; return 0; }

  write_config "$config_file"
  cat > "$env_file" <<EOF
KAFKA_OPTS=-javaagent:${JMX_JAR_PATH}=${port}:${config_file}
EOF
  chown "${RUNTIME_USER}:${RUNTIME_GROUP}" "$env_file"
  chmod 0640 "$env_file"

  mkdir -p "$dropin_dir"
  cat > "$dropin" <<EOF
[Service]
EnvironmentFile=${env_file}
EOF
  chmod 0644 "$dropin"
  log "Configured ${label} JMX exporter for ${service}.service on port ${port}."
}

restart_and_verify() {
  local service="$1"
  local port="$2"
  local label="$3"

  [[ -f "/etc/systemd/system/${service}.service" ]] || return 0
  systemctl restart "$service"
  systemctl is-active --quiet "$service" || die "${service}.service failed to start. Check: journalctl -u ${service} -n 100 --no-pager"

  for _ in 1 2 3 4 5 6; do
    if curl -sf "http://127.0.0.1:${port}/metrics" | grep -Eq '^(kafka_|jvm_|process_|system_)'; then
      log "${label} metrics are reachable at http://127.0.0.1:${port}/metrics"
      return 0
    fi
    sleep 5
  done
  die "${label} metrics did not respond on port ${port}."
}

main() {
  require_root
  validate_inputs

  touch "$LOG_FILE" || LOG_FILE="/tmp/tantor-jmx-exporter-setup.log"
  mkdir -p "$PROM_DIR"
  chown "${RUNTIME_USER}:${RUNTIME_GROUP}" "$PROM_DIR"
  chmod 0750 "$PROM_DIR"

  install_dropin "$BROKER_SERVICE" "$BROKER_METRICS_PORT" "broker"
  if [[ "$ENABLE_CONTROLLER" == "true" ]]; then
    install_dropin "$CONTROLLER_SERVICE" "$CONTROLLER_METRICS_PORT" "controller"
  fi

  systemctl daemon-reload
  if [[ "$RESTART_SERVICES" == "true" ]]; then
    restart_and_verify "$BROKER_SERVICE" "$BROKER_METRICS_PORT" "broker"
    if [[ "$ENABLE_CONTROLLER" == "true" ]]; then
      restart_and_verify "$CONTROLLER_SERVICE" "$CONTROLLER_METRICS_PORT" "controller"
    fi
  else
    log "Drop-ins written. Restart services manually to activate JMX exporter."
  fi

  log "Tantor expects broker JMX metrics on port ${BROKER_METRICS_PORT}."
}

main "$@"
