#!/usr/bin/env bash
set -euo pipefail

requirements='
TANTOR_DB_PASSWORD:12
TANTOR_ENCRYPTION_KEY:32
TANTOR_ENCRYPTION_SALT:16
TANTOR_PROXY_SECRET:32
TANTOR_SSL_KEYSTORE_PASSWORD:12
TANTOR_GRAFANA_PASSWORD:16
'

while IFS=: read -r key minimum; do
  [ -n "$key" ] || continue
  value="${!key-}"
  if [ -z "$value" ]; then
    echo "Required secret $key is missing." >&2
    exit 1
  fi
  if printf '%s' "$value" | grep -Eqi 'change[_-]?me|password|admin|jayesh123|default.?secret'; then
    echo "Required secret $key contains a prohibited placeholder or development default." >&2
    exit 1
  fi
  if [ "${#value}" -lt "$minimum" ]; then
    echo "Required secret $key does not meet the minimum length." >&2
    exit 1
  fi
done <<EOF
$requirements
EOF
