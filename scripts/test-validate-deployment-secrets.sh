#!/usr/bin/env bash
set -euo pipefail

validator="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/validate-deployment-secrets.sh"

if env -i PATH="$PATH" bash "$validator" >/dev/null 2>&1; then
  echo "validator unexpectedly accepted missing secrets" >&2
  exit 1
fi

if env -i PATH="$PATH" \
  TANTOR_DB_PASSWORD='Db_9Yp4vQ2mL8x' \
  TANTOR_ENCRYPTION_KEY='Enc_7gR2mK9pL4vN8xQ6tB3wC5zH1sJ0' \
  TANTOR_ENCRYPTION_SALT='Salt_8qL2nR5vT9x' \
  TANTOR_JWT_SECRET='Jwt_6mQ9vB2xR8pL4nT7wC5zK1sH3gF0' \
  TANTOR_PROXY_SECRET='Proxy_9vL3xQ7mR2nT8pB5wC1zK6sH4gF0' \
  TANTOR_SSL_KEYSTORE_PASSWORD='Store_8pL3vN7x' \
  TANTOR_GRAFANA_PASSWORD='Grafana_7vQ2mL9x' \
  bash "$validator" >/dev/null 2>&1; then
  exit 0
fi

echo "validator rejected valid test-only secrets" >&2
exit 1
