# Validation Report — 1.0.0-prod.11

Validation date: 2026-07-16

## Release scope validated

This release consolidates the issues observed during the successful air-gapped Kafka deployment workflow and includes regressions for the failures encountered during live testing:

- incorrect primary host IP caused by virtual bridge interfaces;
- UI prerequisite contract mismatch;
- agent runtime/state directory permissions;
- artifact transfer from the management environment to the target agent;
- unsafe backend-supplied local artifact staging path;
- artifact service hosted on a separate port from the control API;
- JMX exporter unavailable in an air-gapped environment;
- non-root inspection of restricted KRaft metadata directories;
- uploaded Kafka archives losing executable bits on shell launchers such as `kafka-storage.sh`.

## Automated validation completed

- `GOPROXY=off GOSUMDB=off go test ./...` — PASS
- `GOPROXY=off GOSUMDB=off go test -race ./...` — PASS
- `GOPROXY=off GOSUMDB=off go vet ./...` — PASS
- Shell syntax checks for installer/uninstaller/build/health scripts — PASS
- Offline static Linux AMD64 build — PASS
- Offline static Linux ARM64 build — PASS
- Version metadata check (`1.0.0-prod.11`, `final-production-hardening`) — PASS
- Client-specific IP/public repository scan — PASS
- Regression: unsafe `/srv/tantor-agent` artifact path rejected — PASS
- Regression: JMX optional unless explicitly required — PASS
- Regression: privileged KRaft metadata inspection — PASS
- Regression: Kafka archive script execute bits restored — PASS
- Regression: actual mode-bit repair on non-executable `kafka-storage.sh` fixture — PASS

## Operational note

No software can be guaranteed defect-free in every unknown client environment. This package has been validated against the failures observed in the provided deployment flow and the included automated test suite. Environment-specific backend/API contracts, firewall policies, SELinux policy customizations, storage mounts, and third-party Kafka artifacts can still affect deployment behavior.
