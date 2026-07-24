#!/usr/bin/env bash
set -Eeuo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

echo "[1/5] Verifying checksums"
if [[ -f SHA256SUMS ]]; then
  sha256sum -c SHA256SUMS
else
  echo "SHA256SUMS not present yet; skipping checksum verification"
fi

echo "[2/5] Running tests with module/network access disabled"
GOPROXY=off GOSUMDB=off go test ./...

echo "[3/5] Running go vet with module/network access disabled"
GOPROXY=off GOSUMDB=off go vet ./...

echo "[4/5] Checking shell syntax"
bash -n install-agent.sh uninstall-agent.sh scripts/*.sh

echo "[5/5] Checking release binaries"
for binary in bin/tantor-agent-linux-amd64 bin/tantor-agent-linux-arm64; do
  [[ -x "$binary" ]] || { echo "Missing executable: $binary" >&2; exit 1; }
  file "$binary"
done

echo "Package verification completed successfully."
