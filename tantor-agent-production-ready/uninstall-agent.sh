#!/usr/bin/env bash
set -Eeuo pipefail
[[ $EUID -eq 0 ]] || { echo "Run as root" >&2; exit 1; }
SERVICE_USER="${SERVICE_USER:-tantor-agent}"
systemctl disable --now tantor-agent.service 2>/dev/null || true
rm -f /etc/systemd/system/tantor-agent.service
rm -f "/etc/sudoers.d/tantor-agent-$SERVICE_USER"
systemctl daemon-reload
systemctl reset-failed 2>/dev/null || true
echo "Service and sudo policy removed. Data/config directories were intentionally preserved."
