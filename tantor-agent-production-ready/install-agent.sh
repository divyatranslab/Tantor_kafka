#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "$(uname -m)" in
  x86_64|amd64) BINARY_SOURCE="$SCRIPT_DIR/bin/tantor-agent-linux-amd64" ;;
  aarch64|arm64) BINARY_SOURCE="$SCRIPT_DIR/bin/tantor-agent-linux-arm64" ;;
  *) BINARY_SOURCE="$SCRIPT_DIR/bin/tantor-agent" ;;
esac
[[ -f "$BINARY_SOURCE" ]] || BINARY_SOURCE="$SCRIPT_DIR/bin/tantor-agent"

SERVER_URL=""
SERVICE_USER="tantor-agent"
SERVICE_GROUP=""
AGENT_NAME=""
HOST_ID=""
INSTALL_DIR="/opt/tantor-agent"
CONFIG_DIR="/etc/tantor-agent"
CONFIG_FILE="/etc/tantor-agent/agent.yaml"
DATA_DIR="/var/lib/tantor-agent/data"
LOG_DIR="/var/log/tantor-agent"
ARTIFACTS_DIR="/var/lib/tantor-agent/artifacts"
POLL_INTERVAL="10"
HEARTBEAT_INTERVAL="30"
LOG_LEVEL="INFO"
AUTH_MODE="none"
AUTH_TOKEN_FILE=""
AUTH_USERNAME=""
AUTH_PASSWORD_FILE=""
CA_CERT=""
CLIENT_CERT=""
CLIENT_KEY=""
INSECURE_SKIP_VERIFY="false"
PRIVILEGE_MODE="sudo"
SUDO_PATH="/usr/bin/sudo"
CONFIGURE_SUDOERS="yes"
START_SERVICE="yes"

usage() {
  cat <<'USAGE'
Usage:
  sudo ./install-agent.sh --server-url URL [options]

Required:
  --server-url URL                Management server, e.g. http://MANAGEMENT_SERVER_IP:PORT

Identity and service:
  --service-user USER             Linux account running the agent (default: tantor-agent)
  --service-group GROUP           Linux group (default: user primary group, or same name for a new user)
  --agent-name NAME               Agent display name (default: VM hostname at runtime)
  --host-id ID                    Stable host ID (default: auto-generate and persist)

Paths:
  --install-dir PATH              Binary location (default: /opt/tantor-agent)
  --config-file PATH              Config file (default: /etc/tantor-agent/agent.yaml)
  --data-dir PATH                 Agent data directory
  --log-dir PATH                  Agent log/work directory
  --artifacts-dir PATH            Artifact staging directory

Backend authentication:
  --auth-mode MODE                none|bearer|basic
  --auth-token-file PATH          File containing bearer token; copied securely at install
  --auth-username USER            Basic-auth username
  --auth-password-file PATH       File containing basic-auth password; copied securely at install

HTTPS / mTLS:
  --ca-cert PATH                  CA PEM file
  --client-cert PATH              Client certificate PEM
  --client-key PATH               Client private key PEM
  --insecure-skip-verify BOOL     true|false (default: false; use only for controlled testing)

Runtime:
  --poll-interval SECONDS         Task polling interval (default: 10)
  --heartbeat-interval SECONDS    Heartbeat interval (default: 30)
  --log-level LEVEL               DEBUG|INFO|WARN|ERROR
  --privilege-mode MODE           sudo|direct (default: sudo)
  --sudo-path PATH                sudo executable (default: /usr/bin/sudo)
  --configure-sudoers yes|no      Install agent sudo policy (default: yes)
  --start yes|no                  Enable/start service after install (default: yes)
  -h, --help                      Show this help

The installer is local-only and never downloads packages or code from the internet.
USAGE
}

die() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "[INFO] $*"; }

require_value() {
  [[ $# -ge 2 && -n "${2:-}" ]] || die "Option $1 requires a value"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-url) require_value "$@"; SERVER_URL="$2"; shift 2 ;;
    --service-user) require_value "$@"; SERVICE_USER="$2"; shift 2 ;;
    --service-group) require_value "$@"; SERVICE_GROUP="$2"; shift 2 ;;
    --agent-name) require_value "$@"; AGENT_NAME="$2"; shift 2 ;;
    --host-id) require_value "$@"; HOST_ID="$2"; shift 2 ;;
    --install-dir) require_value "$@"; INSTALL_DIR="$2"; shift 2 ;;
    --config-file) require_value "$@"; CONFIG_FILE="$2"; CONFIG_DIR="$(dirname "$2")"; shift 2 ;;
    --data-dir) require_value "$@"; DATA_DIR="$2"; shift 2 ;;
    --log-dir) require_value "$@"; LOG_DIR="$2"; shift 2 ;;
    --artifacts-dir) require_value "$@"; ARTIFACTS_DIR="$2"; shift 2 ;;
    --poll-interval) require_value "$@"; POLL_INTERVAL="$2"; shift 2 ;;
    --heartbeat-interval) require_value "$@"; HEARTBEAT_INTERVAL="$2"; shift 2 ;;
    --log-level) require_value "$@"; LOG_LEVEL="$2"; shift 2 ;;
    --auth-mode) require_value "$@"; AUTH_MODE="$2"; shift 2 ;;
    --auth-token-file) require_value "$@"; AUTH_TOKEN_FILE="$2"; shift 2 ;;
    --auth-username) require_value "$@"; AUTH_USERNAME="$2"; shift 2 ;;
    --auth-password-file) require_value "$@"; AUTH_PASSWORD_FILE="$2"; shift 2 ;;
    --ca-cert) require_value "$@"; CA_CERT="$2"; shift 2 ;;
    --client-cert) require_value "$@"; CLIENT_CERT="$2"; shift 2 ;;
    --client-key) require_value "$@"; CLIENT_KEY="$2"; shift 2 ;;
    --insecure-skip-verify) require_value "$@"; INSECURE_SKIP_VERIFY="$2"; shift 2 ;;
    --privilege-mode) require_value "$@"; PRIVILEGE_MODE="$2"; shift 2 ;;
    --sudo-path) require_value "$@"; SUDO_PATH="$2"; shift 2 ;;
    --configure-sudoers) require_value "$@"; CONFIGURE_SUDOERS="$2"; shift 2 ;;
    --start) require_value "$@"; START_SERVICE="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1" ;;
  esac
done

[[ $EUID -eq 0 ]] || die "Run this installer as root (for example: sudo ./install-agent.sh ...)"
[[ -n "$SERVER_URL" ]] || die "--server-url is required"
[[ -f "$BINARY_SOURCE" ]] || die "Agent binary not found at $BINARY_SOURCE. Run scripts/build-offline.sh first."
[[ "$SERVER_URL" =~ ^https?://[^[:space:]]+$ ]] || die "--server-url must be an absolute http:// or https:// URL"
[[ "$AUTH_MODE" =~ ^(none|bearer|basic)$ ]] || die "--auth-mode must be none, bearer, or basic"
[[ "$PRIVILEGE_MODE" =~ ^(sudo|direct)$ ]] || die "--privilege-mode must be sudo or direct"
[[ "$CONFIGURE_SUDOERS" =~ ^(yes|no)$ ]] || die "--configure-sudoers must be yes or no"
[[ "$START_SERVICE" =~ ^(yes|no)$ ]] || die "--start must be yes or no"
[[ "$INSECURE_SKIP_VERIFY" =~ ^(true|false)$ ]] || die "--insecure-skip-verify must be true or false"
[[ "$POLL_INTERVAL" =~ ^[0-9]+$ ]] || die "--poll-interval must be an integer"
[[ "$HEARTBEAT_INTERVAL" =~ ^[0-9]+$ ]] || die "--heartbeat-interval must be an integer"

if [[ -z "$SERVICE_GROUP" ]]; then
  if id "$SERVICE_USER" >/dev/null 2>&1; then
    SERVICE_GROUP="$(id -gn "$SERVICE_USER")"
  else
    SERVICE_GROUP="$SERVICE_USER"
  fi
fi
if [[ "$PRIVILEGE_MODE" == "sudo" && "$SERVICE_USER" != "root" ]]; then
  [[ -x "$SUDO_PATH" ]] || die "Configured sudo executable is not available: $SUDO_PATH"
fi

if [[ "$AUTH_MODE" == "bearer" ]]; then
  [[ -n "$AUTH_TOKEN_FILE" && -f "$AUTH_TOKEN_FILE" ]] || die "bearer auth requires an existing --auth-token-file"
fi
if [[ "$AUTH_MODE" == "basic" ]]; then
  [[ -n "$AUTH_USERNAME" ]] || die "basic auth requires --auth-username"
  [[ -n "$AUTH_PASSWORD_FILE" && -f "$AUTH_PASSWORD_FILE" ]] || die "basic auth requires an existing --auth-password-file"
fi
if [[ -n "$CLIENT_CERT" || -n "$CLIENT_KEY" ]]; then
  [[ -n "$CLIENT_CERT" && -f "$CLIENT_CERT" ]] || die "--client-cert file not found"
  [[ -n "$CLIENT_KEY" && -f "$CLIENT_KEY" ]] || die "--client-key file not found"
fi
[[ -z "$CA_CERT" || -f "$CA_CERT" ]] || die "--ca-cert file not found"

if ! getent group "$SERVICE_GROUP" >/dev/null 2>&1; then
  info "Creating system group $SERVICE_GROUP"
  groupadd --system "$SERVICE_GROUP"
fi
if ! id "$SERVICE_USER" >/dev/null 2>&1; then
  info "Creating non-login system user $SERVICE_USER"
  useradd --system --gid "$SERVICE_GROUP" --home-dir "$(dirname "$DATA_DIR")" --no-create-home --shell /usr/sbin/nologin "$SERVICE_USER"
fi

install -d -m 0755 "$INSTALL_DIR" "$CONFIG_DIR"

# The default data/artifact directories live below a dedicated agent state root.
# Explicitly repair that parent as well: creating only the leaf directories is
# insufficient when an older installation left /var/lib/tantor-agent owned by
# another UID or with restrictive permissions.
STATE_ROOT="$(dirname "$DATA_DIR")"
ARTIFACTS_ROOT="$(dirname "$ARTIFACTS_DIR")"
if [[ "$STATE_ROOT" == "$ARTIFACTS_ROOT" && ( "$STATE_ROOT" == "/var/lib/tantor-agent" || "$(basename "$STATE_ROOT")" == "tantor-agent" ) ]]; then
  install -d -o "$SERVICE_USER" -g "$SERVICE_GROUP" -m 0750 "$STATE_ROOT"
  chown "$SERVICE_USER:$SERVICE_GROUP" "$STATE_ROOT"
  chmod 0750 "$STATE_ROOT"
fi

install -d -o "$SERVICE_USER" -g "$SERVICE_GROUP" -m 0750 "$DATA_DIR" "$LOG_DIR" "$ARTIFACTS_DIR"
chown "$SERVICE_USER:$SERVICE_GROUP" "$DATA_DIR" "$LOG_DIR" "$ARTIFACTS_DIR"
chmod 0750 "$DATA_DIR" "$LOG_DIR" "$ARTIFACTS_DIR"

# Upgrade/migration safety for older agent releases that persisted temporary
# staging data and task results directly below the state root. Current releases
# do not depend on these directories, but repairing them prevents permission
# failures if stale files remain during an in-place upgrade.
if [[ "$STATE_ROOT" == "/var/lib/tantor-agent" || "$(basename "$STATE_ROOT")" == "tantor-agent" ]]; then
  LEGACY_STAGING_DIR="$STATE_ROOT/staging"
  LEGACY_TASK_RESULTS_DIR="$STATE_ROOT/task-results"
  install -d -o "$SERVICE_USER" -g "$SERVICE_GROUP" -m 0750 "$LEGACY_STAGING_DIR" "$LEGACY_TASK_RESULTS_DIR"
  chown -R "$SERVICE_USER:$SERVICE_GROUP" "$LEGACY_STAGING_DIR" "$LEGACY_TASK_RESULTS_DIR"
  chmod 0750 "$LEGACY_STAGING_DIR" "$LEGACY_TASK_RESULTS_DIR"
fi

# Replace the executable atomically so an already-running older agent can keep
# running until the controlled restart below instead of being modified in place.
BINARY_TMP="$INSTALL_DIR/.tantor-agent.new.$$"
install -o root -g root -m 0755 "$BINARY_SOURCE" "$BINARY_TMP"
mv -f "$BINARY_TMP" "$INSTALL_DIR/tantor-agent"

CREDENTIAL_DIR="$CONFIG_DIR/credentials"
CERT_DIR="$CONFIG_DIR/certs"
install -d -o root -g "$SERVICE_GROUP" -m 0750 "$CREDENTIAL_DIR" "$CERT_DIR"

TOKEN_DEST=""
PASSWORD_DEST=""
CA_DEST=""
CERT_DEST=""
KEY_DEST=""
if [[ -n "$AUTH_TOKEN_FILE" ]]; then
  TOKEN_DEST="$CREDENTIAL_DIR/backend.token"
  install -o root -g "$SERVICE_GROUP" -m 0640 "$AUTH_TOKEN_FILE" "$TOKEN_DEST"
fi
if [[ -n "$AUTH_PASSWORD_FILE" ]]; then
  PASSWORD_DEST="$CREDENTIAL_DIR/backend.password"
  install -o root -g "$SERVICE_GROUP" -m 0640 "$AUTH_PASSWORD_FILE" "$PASSWORD_DEST"
fi
if [[ -n "$CA_CERT" ]]; then
  CA_DEST="$CERT_DIR/ca.crt"
  install -o root -g "$SERVICE_GROUP" -m 0644 "$CA_CERT" "$CA_DEST"
fi
if [[ -n "$CLIENT_CERT" ]]; then
  CERT_DEST="$CERT_DIR/agent.crt"
  KEY_DEST="$CERT_DIR/agent.key"
  install -o root -g "$SERVICE_GROUP" -m 0644 "$CLIENT_CERT" "$CERT_DEST"
  install -o root -g "$SERVICE_GROUP" -m 0640 "$CLIENT_KEY" "$KEY_DEST"
fi

yaml_quote() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  printf '"%s"' "$value"
}

if [[ -f "$CONFIG_FILE" ]]; then
  cp -p "$CONFIG_FILE" "$CONFIG_FILE.backup.$(date -u +%Y%m%dT%H%M%SZ)"
fi

cat > "$CONFIG_FILE" <<EOF_CONFIG
agent:
  host_id: $(yaml_quote "$HOST_ID")
  agent_name: $(yaml_quote "$AGENT_NAME")
  server_url: $(yaml_quote "$SERVER_URL")
  cert_file: $(yaml_quote "$CERT_DEST")
  key_file: $(yaml_quote "$KEY_DEST")
  ca_cert: $(yaml_quote "$CA_DEST")
  insecure_skip_verify: $INSECURE_SKIP_VERIFY
  poll_interval_seconds: $POLL_INTERVAL
  heartbeat_interval_seconds: $HEARTBEAT_INTERVAL
  log_level: $(yaml_quote "$LOG_LEVEL")

paths:
  data_dir: $(yaml_quote "$DATA_DIR")
  log_dir: $(yaml_quote "$LOG_DIR")
  artifacts_dir: $(yaml_quote "$ARTIFACTS_DIR")

auth:
  mode: $(yaml_quote "$AUTH_MODE")
  token: ""
  token_file: $(yaml_quote "$TOKEN_DEST")
  username: $(yaml_quote "$AUTH_USERNAME")
  password: ""
  password_file: $(yaml_quote "$PASSWORD_DEST")

http:
  request_timeout_seconds: 600
  artifact_timeout_seconds: 1800
  dial_timeout_seconds: 10
  tls_handshake_timeout_seconds: 10
  idle_conn_timeout_seconds: 90
  use_environment_proxy: false

privilege:
  mode: $(yaml_quote "$PRIVILEGE_MODE")
  sudo_path: $(yaml_quote "$SUDO_PATH")
EOF_CONFIG
chown root:"$SERVICE_GROUP" "$CONFIG_FILE"
chmod 0640 "$CONFIG_FILE"

if [[ "$PRIVILEGE_MODE" == "sudo" && "$SERVICE_USER" != "root" && "$CONFIGURE_SUDOERS" == "yes" ]]; then
  command -v visudo >/dev/null 2>&1 || die "visudo is required to safely install the sudo policy"
  SUDOERS_FILE="/etc/sudoers.d/tantor-agent-$SERVICE_USER"
  COMMANDS=(bash mkdir test tar restorecon chcon mv chmod cp chown systemctl journalctl ln cat rm fuser ss systemd-run useradd)
  ALLOWED=()
  for command_name in "${COMMANDS[@]}"; do
    if command_path="$(type -P "$command_name" 2>/dev/null)"; then
      ALLOWED+=("$command_path *")
    fi
  done
  [[ ${#ALLOWED[@]} -gt 0 ]] || die "No privileged commands were found for sudo policy generation"
  {
    echo "# Managed by Tantor Agent installer."
    echo "# The current deployment feature set includes privileged host changes."
    printf 'Cmnd_Alias TANTOR_AGENT_COMMANDS = '
    local_sep=""
    for entry in "${ALLOWED[@]}"; do
      printf '%s%s' "$local_sep" "$entry"
      local_sep=", "
    done
    printf '\n'
    echo "$SERVICE_USER ALL=(root) NOPASSWD: TANTOR_AGENT_COMMANDS"
    echo "Defaults:$SERVICE_USER !requiretty"
  } > "$SUDOERS_FILE.tmp"
  chmod 0440 "$SUDOERS_FILE.tmp"
  visudo -cf "$SUDOERS_FILE.tmp" >/dev/null
  mv "$SUDOERS_FILE.tmp" "$SUDOERS_FILE"
  chmod 0440 "$SUDOERS_FILE"
fi

cat > /etc/systemd/system/tantor-agent.service <<EOF_SERVICE
[Unit]
Description=Tantor Kafka Management Agent
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=60s
StartLimitBurst=10

[Service]
Type=simple
User=$SERVICE_USER
Group=$SERVICE_GROUP
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/tantor-agent -config $CONFIG_FILE
Restart=on-failure
RestartSec=5s
TimeoutStopSec=30s
KillSignal=SIGTERM
UMask=0027
LimitNOFILE=1024000
LimitNPROC=1024000
Environment=PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

[Install]
WantedBy=multi-user.target
EOF_SERVICE
chmod 0644 /etc/systemd/system/tantor-agent.service
systemctl daemon-reload

info "Validating generated configuration as service user"
if command -v runuser >/dev/null 2>&1; then
  runuser -u "$SERVICE_USER" -- "$INSTALL_DIR/tantor-agent" -config "$CONFIG_FILE" -check-config
else
  su -s /bin/sh -c "'$INSTALL_DIR/tantor-agent' -config '$CONFIG_FILE' -check-config" "$SERVICE_USER"
fi

if [[ "$START_SERVICE" == "yes" ]]; then
  # enable --now does not restart an already-running old agent process. Always
  # perform an explicit restart after replacing the binary/config so the UI is
  # guaranteed to execute the newly installed release.
  systemctl enable tantor-agent.service >/dev/null
  systemctl restart tantor-agent.service
  if ! systemctl is-active --quiet tantor-agent.service; then
    systemctl status tantor-agent.service --no-pager || true
    journalctl -u tantor-agent.service -n 80 --no-pager || true
    die "tantor-agent.service did not become active"
  fi
fi

cat <<EOF_DONE

Tantor Agent installation completed.
  Server URL : $SERVER_URL
  Service user: $SERVICE_USER
  Config      : $CONFIG_FILE
  Binary      : $INSTALL_DIR/tantor-agent

Commands:
  systemctl status tantor-agent --no-pager
  journalctl -u tantor-agent -f
  $INSTALL_DIR/tantor-agent -config $CONFIG_FILE -check-config
EOF_DONE
