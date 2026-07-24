#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${VERSION:-1.0.0-prod.11}"
COMMIT="${COMMIT:-final-production-hardening}"
BUILD_DATE="${BUILD_DATE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"
GOARCH_VALUE="${GOARCH:-amd64}"

mkdir -p bin

echo "Building Tantor Agent with network access disabled..."
CGO_ENABLED=0 \
GOOS=linux \
GOARCH="$GOARCH_VALUE" \
GOPROXY=off \
GOSUMDB=off \
go build -trimpath -buildvcs=false \
  -ldflags "-s -w -X main.version=$VERSION -X main.commit=$COMMIT -X main.buildDate=$BUILD_DATE" \
  -o "bin/tantor-agent-linux-$GOARCH_VALUE" ./cmd/agent

cp "bin/tantor-agent-linux-$GOARCH_VALUE" bin/tantor-agent
chmod 0755 bin/tantor-agent "bin/tantor-agent-linux-$GOARCH_VALUE"

echo "Built: $ROOT_DIR/bin/tantor-agent-linux-$GOARCH_VALUE"
case "$(uname -m):$GOARCH_VALUE" in
  x86_64:amd64|amd64:amd64|aarch64:arm64|arm64:arm64)
    "$ROOT_DIR/bin/tantor-agent-linux-$GOARCH_VALUE" -version
    ;;
  *)
    go version -m "$ROOT_DIR/bin/tantor-agent-linux-$GOARCH_VALUE" | head -n 3
    ;;
esac
