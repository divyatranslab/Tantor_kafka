#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'EOF'
Usage:
  ./install-discovery-agent.sh --server-url https://<tantor-server-ip>:<port> [options]

Options:
  --server-url URL          Tantor backend URL. Required.
  --environment NAME       development, sit, uat, or production. Required.
  --binary PATH            Path to uploaded agent binary. Default: /srv/tantor-discovery-agent-linux
  --agent-dir PATH         Install directory. Default: /srv/tantor-discovery-agent
  --service-name NAME      systemd service name. Default: tantor-discovery-agent
  --runtime-user USER      Linux user for systemd service. Default: root
  --log-dir PATH           Persistent log directory. Default: /var/log/tantor/discovery-agent
  --host-id ID             Discovery agent host ID. Default: discovery-<hostname>
  --agent-name NAME        Discovery agent display name. Default: tantor-discovery-<hostname>
  --node-name NAME         Reported node name. Default: OS hostname
  --kafka-home PATH        Kafka install directory, for example /opt/kafka_2.13-4.1.0.
  --kafka-config FILE      Kafka config file. Can be repeated.
  --kafka-data-dirs CSV    Kafka data directories to report when config omits them.
  --kafka-log-dirs CSV     Kafka log directories to report when config omits them.
  --scan-paths CSV         Comma-separated scan dirs. Default: /opt,/opt_apb,/app,/srv,/data,/usr/local,/usr/share,/var/lib
  --interval DURATION      Discovery interval. Default: 15s
  --task-poll DURATION     Task poll interval. Default: 5s
  --kafka-service NAME     Kafka systemd service for restart. Default: kafka.service
  --metrics-url URL        Local JMX/Prometheus URL. Default: http://localhost:7071/metrics
  --disable-metrics        Do not poll local metrics endpoint.
  --skip-precheck          Skip startup precheck output.
  --tls-ca PATH            Control-plane CA certificate. Required.
  --tls-cert PATH          Discovery-agent client certificate. Required.
  --tls-key PATH           Discovery-agent private key. Required.
  --foreground             Run once in foreground instead of installing systemd.
  -h, --help               Show this help.
EOF
}

SERVER_URL=""
ENVIRONMENT=""
BINARY="/srv/tantor-discovery-agent-linux"
AGENT_DIR="/srv/tantor-discovery-agent"
SERVICE_NAME="tantor-discovery-agent"
RUNTIME_USER="root"
LOG_DIR="/var/log/tantor/discovery-agent"
HOST_ID=""
AGENT_NAME=""
NODE_NAME=""
KAFKA_HOME=""
KAFKA_CONFIG_FILES=()
KAFKA_DATA_DIRS=""
KAFKA_LOG_DIRS=""
SCAN_PATHS="/opt,/opt_apb,/app,/srv,/data,/usr/local,/usr/share,/var/lib"
INTERVAL="15s"
TASK_POLL="5s"
KAFKA_SERVICE="kafka.service"
METRICS_URL="http://localhost:7071/metrics"
DISABLE_METRICS="false"
SKIP_PRECHECK="false"
TLS_CA=""
TLS_CERT=""
TLS_KEY=""
FOREGROUND="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-url) SERVER_URL="${2:-}"; shift 2 ;;
    --environment) ENVIRONMENT="${2:-}"; shift 2 ;;
    --binary) BINARY="${2:-}"; shift 2 ;;
    --agent-dir) AGENT_DIR="${2:-}"; shift 2 ;;
    --service-name) SERVICE_NAME="${2:-}"; shift 2 ;;
    --runtime-user) RUNTIME_USER="${2:-}"; shift 2 ;;
    --log-dir) LOG_DIR="${2:-}"; shift 2 ;;
    --host-id) HOST_ID="${2:-}"; shift 2 ;;
    --agent-name) AGENT_NAME="${2:-}"; shift 2 ;;
    --node-name) NODE_NAME="${2:-}"; shift 2 ;;
    --kafka-home) KAFKA_HOME="${2:-}"; shift 2 ;;
    --kafka-config) KAFKA_CONFIG_FILES+=("${2:-}"); shift 2 ;;
    --kafka-data-dirs) KAFKA_DATA_DIRS="${2:-}"; shift 2 ;;
    --kafka-log-dirs) KAFKA_LOG_DIRS="${2:-}"; shift 2 ;;
    --scan-paths) SCAN_PATHS="${2:-}"; shift 2 ;;
    --interval) INTERVAL="${2:-}"; shift 2 ;;
    --task-poll) TASK_POLL="${2:-}"; shift 2 ;;
    --kafka-service) KAFKA_SERVICE="${2:-}"; shift 2 ;;
    --metrics-url) METRICS_URL="${2:-}"; shift 2 ;;
    --disable-metrics) DISABLE_METRICS="true"; shift ;;
    --skip-precheck) SKIP_PRECHECK="true"; shift ;;
    --tls-ca) TLS_CA="${2:-}"; shift 2 ;;
    --tls-cert) TLS_CERT="${2:-}"; shift 2 ;;
    --tls-key) TLS_KEY="${2:-}"; shift 2 ;;
    --foreground) FOREGROUND="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$SERVER_URL" ]]; then
  echo "ERROR: --server-url is required." >&2
  usage
  exit 2
fi
if [[ "$SERVER_URL" != https://* ]]; then
  echo "ERROR: --server-url must use https://." >&2
  exit 2
fi
case "$ENVIRONMENT" in development|sit|uat|production) ;; *) echo "ERROR: --environment must be development, sit, uat, or production." >&2; exit 2 ;; esac
for tls_file in "$TLS_CA" "$TLS_CERT" "$TLS_KEY"; do
  if [[ -z "$tls_file" || ! -s "$tls_file" ]]; then
    echo "ERROR: --tls-ca, --tls-cert, and --tls-key must name non-empty pre-provisioned files." >&2
    exit 2
  fi
done

HOSTNAME_VALUE="$(hostname -f 2>/dev/null || hostname)"
HOST_ID="${HOST_ID:-discovery-${HOSTNAME_VALUE//[^A-Za-z0-9_-]/-}}"
AGENT_NAME="${AGENT_NAME:-tantor-discovery-${HOSTNAME_VALUE//[^A-Za-z0-9_-]/-}}"
NODE_NAME="${NODE_NAME:-$HOSTNAME_VALUE}"
RESTART_COMMAND="systemctl restart ${KAFKA_SERVICE}"
SYSTEMD_USE_SUDO="false"

if [[ ! -f "$BINARY" ]]; then
  echo "ERROR: binary not found at $BINARY" >&2
  echo "Copy it first, for example: scp tantor-discovery-agent-linux root@<vm>:/srv/" >&2
  exit 1
fi

if [[ "$(id -u)" -ne 0 ]]; then
  echo "ERROR: run this installer as root, or use sudo." >&2
  exit 1
fi

if [[ "$LOG_DIR" != /* ]]; then
  echo "ERROR: --log-dir must be an absolute path." >&2
  exit 2
fi

if ! id "$RUNTIME_USER" >/dev/null 2>&1; then
  echo "ERROR: runtime user '$RUNTIME_USER' does not exist." >&2
  exit 1
fi
RUNTIME_GROUP="$(id -gn "$RUNTIME_USER")"
LOG_FILE="${LOG_DIR}/${SERVICE_NAME}.log"

systemctl disable --now "${SERVICE_NAME}.service" >/dev/null 2>&1 || true

mkdir -p "$AGENT_DIR/logs"
mkdir -p "$LOG_DIR"
touch "$LOG_FILE"
chown "$RUNTIME_USER:$RUNTIME_GROUP" "$LOG_DIR" "$LOG_FILE"
chmod 0750 "$LOG_DIR"
chmod 0640 "$LOG_FILE"
install -m 0755 "$BINARY" "$AGENT_DIR/tantor-discovery-agent-linux"

{
  echo "discovery:"
  echo "  environment: \"${ENVIRONMENT}\""
  echo "  host_id: \"${HOST_ID}\""
  echo "  agent_name: \"${AGENT_NAME}\""
  echo "  server_url: \"${SERVER_URL}\""
  echo "  interval: \"${INTERVAL}\""
  echo "  task_poll_interval: \"${TASK_POLL}\""
	cat <<'HTTP_CONFIG'
  command_timeout: "30s"
  http:
    connect_timeout: "3s"
    tls_handshake_timeout: "5s"
    response_header_timeout: "5s"
    request_timeout: "10s"
    retry_total_timeout: "25s"
    retry_max_attempts: 3
    retry_initial_backoff: "250ms"
    retry_max_backoff: "2s"
    circuit_failure_threshold: 5
    circuit_open_duration: "30s"
HTTP_CONFIG
  echo "  kafka_home: \"${KAFKA_HOME}\""
  echo "  kafka_config_files:"
  for config_file in "${KAFKA_CONFIG_FILES[@]}"; do
    [[ -n "$config_file" ]] && echo "    - \"${config_file}\""
  done
  echo "  kafka_data_dirs: \"${KAFKA_DATA_DIRS}\""
  echo "  kafka_log_dirs: \"${KAFKA_LOG_DIRS}\""
  echo "  scan_paths:"
  IFS=',' read -r -a paths <<< "$SCAN_PATHS"
  for path in "${paths[@]}"; do
    trimmed="$(echo "$path" | xargs)"
    [[ -n "$trimmed" ]] && echo "    - \"${trimmed}\""
  done
  echo "  node_name: \"${NODE_NAME}\""
  echo "  restart_command: \"${RESTART_COMMAND}\""
  echo "  systemd_use_sudo: ${SYSTEMD_USE_SUDO}"
  echo "  metrics_url: \"${METRICS_URL}\""
  echo "  disable_metrics: ${DISABLE_METRICS}"
  echo "  skip_precheck: ${SKIP_PRECHECK}"
  echo "  tls_ca_cert: \"${TLS_CA}\""
  echo "  tls_client_cert: \"${TLS_CERT}\""
  echo "  tls_client_key: \"${TLS_KEY}\""
} > "$AGENT_DIR/discovery.yaml"

chmod 0640 "$AGENT_DIR/discovery.yaml"

if [[ "$FOREGROUND" == "true" ]]; then
  echo "Running discovery agent in foreground..."
  exec "$AGENT_DIR/tantor-discovery-agent-linux" -config "$AGENT_DIR/discovery.yaml"
fi

cat > "/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=Tantor Discovery Agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=${RUNTIME_USER}
WorkingDirectory=${AGENT_DIR}
ExecStart=${AGENT_DIR}/tantor-discovery-agent-linux -config ${AGENT_DIR}/discovery.yaml
Restart=always
RestartSec=5
TimeoutStopSec=40
KillSignal=SIGTERM
LimitNOFILE=1024000
LimitNPROC=1024000
StandardOutput=append:${LOG_FILE}
StandardError=append:${LOG_FILE}

[Install]
WantedBy=multi-user.target
EOF

cat > "/etc/logrotate.d/${SERVICE_NAME}" <<EOF
${LOG_FILE} {
    daily
    rotate 14
    maxsize 50M
    compress
    delaycompress
    missingok
    notifempty
    copytruncate
    create 0640 ${RUNTIME_USER} ${RUNTIME_GROUP}
}
EOF
chmod 0644 "/etc/logrotate.d/${SERVICE_NAME}"

systemctl daemon-reload
systemctl enable --now "${SERVICE_NAME}.service"

echo
echo "Tantor Discovery Agent installed."
echo "Config : ${AGENT_DIR}/discovery.yaml"
echo "Status : systemctl status ${SERVICE_NAME}.service --no-pager"
echo "Logs   : tail -F ${LOG_FILE}"
echo "Journal: journalctl -u ${SERVICE_NAME}.service --no-pager"
