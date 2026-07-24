#!/usr/bin/env bash
set -Eeuo pipefail
SERVICE="${SERVICE:-tantor-agent.service}"
CONFIG="${CONFIG:-/etc/tantor-agent/agent.yaml}"
BINARY="${BINARY:-/opt/tantor-agent/tantor-agent}"

printf '%s\n' '=== Tantor Agent Health Check ==='
"$BINARY" -version
"$BINARY" -config "$CONFIG" -check-config
if systemctl is-active --quiet "$SERVICE"; then
  echo "service: ACTIVE"
else
  echo "service: NOT ACTIVE"
  systemctl status "$SERVICE" --no-pager || true
  exit 1
fi
journalctl -u "$SERVICE" -n 30 --no-pager || true
