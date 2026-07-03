#!/bin/bash
# Manual installation script for Tantor Agent and Discovery Agent
# Designed for Air-Gapped Environments with Prerequisite Checks

set -e

# Default variables (can be modified before running)
CONTROL_PLANE_URL="http://192.168.3.107:8443"
DISCOVERY_SERVER_URL="http://192.168.3.107:8443"

BASE_DIR="/opt/tantor-agent"
INSTALL_DIR="$BASE_DIR/bin"
LOG_DIR="$BASE_DIR/logs"
DATA_DIR="$BASE_DIR/data"
ARTIFACTS_DIR="$BASE_DIR/artifacts"
CERT_DIR="$BASE_DIR/certs"
CONFIG_DIR="$BASE_DIR/configs"
DISCOVERY_LOG_DIR="$BASE_DIR/logs/discovery"

# Get current hostname for config
HOSTNAME=$(hostname)

check_prerequisites() {
    echo "==========================================="
    echo "Running prerequisite checks..."
    echo "==========================================="

    # 1. Check OS Distribution (RedHat/CentOS family)
    echo "Checking OS Distribution..."
    if [ -f /etc/os-release ]; then
        . /etc/os-release
        if [[ "$ID_LIKE" != *"rhel"* && "$ID_LIKE" != *"centos"* && "$ID" != *"rhel"* && "$ID" != *"centos"* ]]; then
             echo "Warning: This script is optimized for RHEL/CentOS systems. Detected OS: $PRETTY_NAME"
             echo "You may proceed, but some paths or systemd behaviors might differ."
        else
             echo "OS check passed: $PRETTY_NAME"
        fi
    else
        echo "Warning: Could not determine OS distribution."
    fi

    # 2. Check free disk space on /var or / (needs 1GB = 1048576 KB)
    echo "Checking disk space (requires 1GB on /var)..."
    local var_avail=$(df -k /var | awk 'NR==2 {print $4}')
    if [ -z "$var_avail" ] || [ "$var_avail" -lt 1048576 ]; then
        echo "Error: Not enough free space on /var. Requires at least 1GB."
        exit 1
    fi
    echo "Disk space check passed."

    # 3. Check connectivity to Control Plane and Discovery Server
    # Extract host and port
    local cp_host=$(echo $CONTROL_PLANE_URL | sed -E 's|https?://([^:]+).*|\1|')
    local cp_port=$(echo $CONTROL_PLANE_URL | sed -E 's|.*:([0-9]+).*|\1|' | sed -E 's|/.*||') # Ensure trailing paths are stripped
    
    local disc_host=$(echo $DISCOVERY_SERVER_URL | sed -E 's|https?://([^:]+).*|\1|')
    local disc_port=$(echo $DISCOVERY_SERVER_URL | sed -E 's|.*:([0-9]+).*|\1|' | sed -E 's|/.*||')

    echo "Checking connectivity to Control Plane ($cp_host:$cp_port)..."
    if ! timeout 5 bash -c "</dev/tcp/$cp_host/$cp_port" 2>/dev/null; then
        echo "Error: Cannot connect to Control Plane at $cp_host:$cp_port"
        echo "Please verify network connectivity or check if the IP is reachable in this air-gapped network."
        exit 1
    fi
    echo "Control Plane connectivity passed."

    echo "Checking connectivity to Discovery Server ($disc_host:$disc_port)..."
    if ! timeout 5 bash -c "</dev/tcp/$disc_host/$disc_port" 2>/dev/null; then
        echo "Error: Cannot connect to Discovery Server at $disc_host:$disc_port"
        echo "Please verify network connectivity."
        exit 1
    fi
    echo "Discovery Server connectivity passed."

    echo "==========================================="
    echo "All prerequisite checks passed successfully!"
    echo "==========================================="
    echo ""
}

# Run the prerequisite checks first
check_prerequisites

echo "Starting Tantor Agents Manual Installation..."

# 1. Ensure directories exist
echo "Creating necessary directories..."
mkdir -p "$INSTALL_DIR" "$LOG_DIR" "$DATA_DIR" "$ARTIFACTS_DIR" "$CERT_DIR" "$CONFIG_DIR" "$DISCOVERY_LOG_DIR"
chmod 0755 "$INSTALL_DIR" "$LOG_DIR" "$DATA_DIR" "$ARTIFACTS_DIR" "$CERT_DIR" "$CONFIG_DIR" "$DISCOVERY_LOG_DIR"

# 2. Copy binaries
echo "Copying agent binaries..."
if [ ! -f "./tantor-agent-linux" ]; then
    echo "Error: tantor-agent-linux binary not found in the current directory."
    echo "Please place it in the same directory as this script."
    exit 1
fi
if [ ! -f "./tantor-discovery-agent-linux" ]; then
    echo "Error: tantor-discovery-agent-linux binary not found in the current directory."
    echo "Please place it in the same directory as this script."
    exit 1
fi

cp ./tantor-agent-linux "$INSTALL_DIR/tantor-agent"
chmod 0755 "$INSTALL_DIR/tantor-agent"

cp ./tantor-discovery-agent-linux "$INSTALL_DIR/tantor-discovery-agent"
chmod 0755 "$INSTALL_DIR/tantor-discovery-agent"

# 3. Create agent.yaml configuration
echo "Generating agent.yaml configuration..."
cat <<EOF > "$CONFIG_DIR/agent.yaml"
agent:
  host_id: "$HOSTNAME"
  server_url: "$CONTROL_PLANE_URL"
  cert_file: "$CERT_DIR/agent.crt"
  key_file: "$CERT_DIR/agent.key"
  ca_cert: "$CERT_DIR/ca.crt"
  poll_interval_seconds: 10
  log_level: "INFO"

paths:
  data_dir: "$DATA_DIR"
  log_dir: "$LOG_DIR"
  artifacts_dir: "$ARTIFACTS_DIR"
EOF
chmod 0644 "$CONFIG_DIR/agent.yaml"

# 4. Create discovery.yaml configuration
echo "Generating discovery.yaml configuration..."
cat <<EOF > "$CONFIG_DIR/discovery.yaml"
discovery:
  server_url: "$DISCOVERY_SERVER_URL"
  scan_paths:
    - "/srv/apps"
    - "/data/apps"
    - "/opt"
  interval: "15s"
  node_name: "$HOSTNAME"
  restart_command: "systemctl restart kafka"
EOF
chmod 0644 "$CONFIG_DIR/discovery.yaml"

# 5. Create tantor-agent systemd service
echo "Generating tantor-agent systemd service..."
cat <<EOF > /etc/systemd/system/tantor-agent.service
[Unit]
Description=Tantor Agent Service
After=network.target

[Service]
Type=simple
ExecStart=$INSTALL_DIR/tantor-agent -config $CONFIG_DIR/agent.yaml
Restart=on-failure
RestartSec=5
User=root
WorkingDirectory=$INSTALL_DIR

[Install]
WantedBy=multi-user.target
EOF
chmod 0644 /etc/systemd/system/tantor-agent.service

# 6. Create tantor-discovery-agent systemd service
echo "Generating tantor-discovery-agent systemd service..."
cat <<EOF > /etc/systemd/system/tantor-discovery-agent.service
[Unit]
Description=Tantor Discovery Agent Service
After=network.target

[Service]
Type=simple
ExecStart=$INSTALL_DIR/tantor-discovery-agent -config $CONFIG_DIR/discovery.yaml
Restart=on-failure
RestartSec=5
User=root
WorkingDirectory=$INSTALL_DIR

[Install]
WantedBy=multi-user.target
EOF
chmod 0644 /etc/systemd/system/tantor-discovery-agent.service

# 7. Start and enable services
echo "Reloading systemd daemon..."
systemctl daemon-reload

echo "Starting and enabling tantor-agent service..."
systemctl enable --now tantor-agent

echo "Starting and enabling tantor-discovery-agent service..."
systemctl enable --now tantor-discovery-agent

echo "Installation complete!"
