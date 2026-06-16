#!/usr/bin/env bash
# Tantor Agent Installer Script
# Target OS: RHEL/CentOS, Ubuntu/Debian
# Run as root

set -e

if [ "$EUID" -ne 0 ]; then
  echo "Please run as root"
  exit 1
fi

TANTOR_USER="tantor"
TANTOR_HOME="/opt/tantor"
AGENT_BIN_URL=${AGENT_BIN_URL:-"https://tantor-server:8443/downloads/tantor-agent"}
SERVER_URL=${SERVER_URL:-"https://tantor-server:8443"}
CERT_PATH="/etc/tantor/certs"
AGENT_DATA_DIR="/var/lib/tantor/agent/data"
AGENT_ARTIFACTS_DIR="/var/lib/tantor/agent/artifacts"
AGENT_LOG_DIR="/var/log/tantor"

echo "=== Tantor Agent Installer ==="

# 1. Create tantor user
if id "$TANTOR_USER" &>/dev/null; then
    echo "User $TANTOR_USER already exists"
else
    useradd -r -m -d $TANTOR_HOME -s /bin/bash $TANTOR_USER
    echo "Created user: $TANTOR_USER"
fi

# Passwordless sudo for tantor (required by architecture)
echo "$TANTOR_USER ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/99-tantor-agent
chmod 0440 /etc/sudoers.d/99-tantor-agent

# 2. Install agent binary
echo "Downloading agent..."
mkdir -p $TANTOR_HOME/bin
curl -k -s -o $TANTOR_HOME/bin/tantor-agent $AGENT_BIN_URL
chmod +x $TANTOR_HOME/bin/tantor-agent
chown -R $TANTOR_USER:$TANTOR_USER $TANTOR_HOME

# Runtime directories used by the agent for downloads, extraction, and logs.
mkdir -p "$AGENT_DATA_DIR" "$AGENT_ARTIFACTS_DIR" "$AGENT_LOG_DIR"
chown -R $TANTOR_USER:$TANTOR_USER /var/lib/tantor "$AGENT_LOG_DIR"

# 3. Configure certificates and agent config
echo "Setting up certificates and configs..."
mkdir -p $CERT_PATH
mkdir -p /etc/tantor/config

# In a real air-gapped flow, certs would be injected here from an HSM or config management
echo "[MOCK] Generating self-signed certs for mTLS..."
openssl req -x509 -newkey rsa:4096 -keyout $CERT_PATH/agent.key -out $CERT_PATH/agent.crt -days 365 -nodes -subj "/CN=$(hostname)" 2>/dev/null
cp $CERT_PATH/agent.crt $CERT_PATH/ca.crt # Mock CA

HOST_ID="agent-$(hostname)"

cat <<EOF > /etc/tantor/config/agent.yaml
agent:
  host_id: "$HOST_ID"
  server_url: "$SERVER_URL"
  cert_file: "$CERT_PATH/agent.crt"
  key_file: "$CERT_PATH/agent.key"
  ca_cert: "$CERT_PATH/ca.crt"
  poll_interval_seconds: 15
  log_level: "INFO"

paths:
  data_dir: "$AGENT_DATA_DIR"
  log_dir: "$AGENT_LOG_DIR"
  artifacts_dir: "$AGENT_ARTIFACTS_DIR"
EOF

chown -R $TANTOR_USER:$TANTOR_USER /etc/tantor
chmod 600 $CERT_PATH/agent.key

# 4. Create systemd service
echo "Creating systemd service..."
cat <<EOF > /etc/systemd/system/tantor-agent.service
[Unit]
Description=Tantor Agent
After=network.target

[Service]
Type=simple
User=$TANTOR_USER
Group=$TANTOR_USER
ExecStart=$TANTOR_HOME/bin/tantor-agent -config /etc/tantor/config/agent.yaml
Restart=on-failure
RestartSec=5s

[Install]
WantedBy=multi-user.target
EOF

# 5. Start Service & Register Host
echo "Starting Tantor Agent..."
systemctl daemon-reload
systemctl enable --now tantor-agent
systemctl status tantor-agent --no-pager | head -n 5

echo "================================================="
echo "Tantor Agent successfully installed and started!"
echo "Host ID: $HOST_ID"
echo "It will automatically register with $SERVER_URL"
echo "================================================="
