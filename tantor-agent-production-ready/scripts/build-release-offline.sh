#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

VERSION="${VERSION:-1.0.0-prod.11}"
COMMIT="${COMMIT:-final-production-hardening}"
BUILD_DATE="${BUILD_DATE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}"

for arch in amd64 arm64; do
  echo "Building linux/$arch with all module/network access disabled..."
  VERSION="$VERSION" COMMIT="$COMMIT" BUILD_DATE="$BUILD_DATE" GOARCH="$arch" ./scripts/build-offline.sh
done
cp bin/tantor-agent-linux-amd64 bin/tantor-agent
chmod 0755 bin/tantor-agent

echo "Release binaries built:"
ls -lh bin/tantor-agent bin/tantor-agent-linux-amd64 bin/tantor-agent-linux-arm64
