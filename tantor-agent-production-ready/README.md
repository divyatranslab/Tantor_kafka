# Tantor Agent — Air-Gapped Linux Production Package

This package keeps the existing Kafka management functionality while removing client-specific deployment assumptions from the agent runtime.

## What this package is designed for

- The management/backend server can be on any reachable IP or DNS name and any port.
- The agent can be installed on any supported Linux VM without changing or recompiling source code.
- The client environment can be fully air-gapped. The agent never needs to pull Go modules or software from the public internet.
- The agent service can run as a dedicated non-root Linux account.
- Backend authentication is optional and runtime-configurable: none, bearer token, Basic authentication, or HTTPS/mTLS.
- The original task/API contract and deployment commands are retained.

## Connection model

Normal runtime communication is outbound from the managed VM to the management server:

```text
Managed VM (agent)  --->  Management server/backend
       208           --->          191
```

The agent does not need SSH credentials to communicate with the backend. SSH is not part of the normal agent-to-server flow. The only network destination used for control-plane calls is the configured `agent.server_url`.

On multi-interface Linux VMs, the agent orders registration addresses by the actual route to the configured management server and filters common virtual bridge/container interfaces. The server IP and agent host IP are intentionally different: for example, an agent registers its own routed client-network address as the primary host address while using the separately configured management-server URL for control-plane traffic.

Kafka artifacts uploaded through the UI can be referenced by either `artifact_id` or a backend-provided artifact download URL. When an artifact ID is present it is the canonical identity and the agent resolves it through management-server artifact endpoints. When the backend provides a direct artifact-service URL (for example, a separate repository service/port on the same management host), the agent uses that URL first. Downloads are staged only under the configured agent-owned artifacts directory. No public internet access is required or assumed.

## Package layout

```text
bin/
  tantor-agent                  default amd64 release binary
  tantor-agent-linux-amd64      static Linux x86_64 binary
  tantor-agent-linux-arm64      static Linux ARM64 binary
cmd/                            Go entry point
internal/                       agent implementation and existing deployment engines
pkg/                            API/checksum/logger packages
configs/agent.yaml              generic example configuration
install-agent.sh                local Linux installer
uninstall-agent.sh              removes service and installer-created sudo policy
scripts/build-offline.sh        builds one architecture with network access disabled
scripts/build-release-offline.sh builds amd64 + arm64 with network access disabled
scripts/health-check.sh         local runtime health check
docs/                           security and configuration notes
SHA256SUMS                      package integrity manifest
```

## Recommended installation: VM 208 connecting to backend 191

Copy this ZIP into the air-gapped environment using the client's approved transfer process, unzip it on VM 208, then run locally on VM 208:

```bash
cd tantor-agent-production-ready
sha256sum -c SHA256SUMS

sudo ./install-agent.sh \
  --server-url http://MANAGEMENT_SERVER_IP:8080 \
  --service-user tantor-agent
```

Replace `8080` with the actual backend port. For HTTPS:

```bash
sudo ./install-agent.sh \
  --server-url https://MANAGEMENT_SERVER_IP:8443 \
  --service-user tantor-agent \
  --ca-cert /secure-transfer/client-ca.crt
```

The installer:

1. Detects the VM CPU architecture and selects the matching static binary.
2. Creates the service account when it does not already exist.
3. Creates writable data/artifact/log directories owned by the service account.
4. Writes the runtime configuration with the supplied server URL and options.
5. Generates a stable host ID on first validation/start when one was not supplied.
6. Installs a systemd service.
7. Optionally installs the sudo policy required by the existing privileged deployment features.
8. Validates the configuration as the actual service user before starting the service.

## Moving to another client

No source-code change is required. Only runtime values change:

```bash
sudo ./install-agent.sh \
  --server-url https://10.50.20.15:9443 \
  --service-user client-kafka-agent \
  --data-dir /data/tantor-agent \
  --artifacts-dir /data/tantor-artifacts \
  --ca-cert /secure/client-ca.pem
```

The server IP, port, service user, directories, TLS settings, credentials, host ID, polling interval, and privilege mode are not compiled into the binary.

## Backend authentication

### Existing backend with no authentication

```bash
sudo ./install-agent.sh \
  --server-url http://MANAGEMENT_SERVER_IP:8080 \
  --auth-mode none
```

This preserves the current unauthenticated API behavior.

### Bearer token

Store the token in a protected file and pass the file path, not the secret itself:

```bash
sudo ./install-agent.sh \
  --server-url https://control-plane.internal:8443 \
  --auth-mode bearer \
  --auth-token-file /secure-transfer/agent.token \
  --ca-cert /secure-transfer/ca.crt
```

### Basic authentication

```bash
sudo ./install-agent.sh \
  --server-url https://control-plane.internal:8443 \
  --auth-mode basic \
  --auth-username agent-208 \
  --auth-password-file /secure-transfer/backend.password \
  --ca-cert /secure-transfer/ca.crt
```

Actual passwords/tokens do not need to appear in the long-running agent command line.

## mTLS

```bash
sudo ./install-agent.sh \
  --server-url https://control-plane.internal:8443 \
  --ca-cert /secure-transfer/ca.crt \
  --client-cert /secure-transfer/agent.crt \
  --client-key /secure-transfer/agent.key
```

TLS certificate verification is enabled by default. `--insecure-skip-verify true` exists only for controlled troubleshooting and should not be used for production.

## Existing limited service user

The installer can run the service under an existing account:

```bash
sudo ./install-agent.sh \
  --server-url http://MANAGEMENT_SERVER_IP:8080 \
  --service-user kafka-agent \
  --service-group kafka-agent
```

For a client-managed sudo policy:

```bash
sudo ./install-agent.sh \
  --server-url http://MANAGEMENT_SERVER_IP:8080 \
  --service-user kafka-agent \
  --configure-sudoers no
```

The agent never accepts or stores a sudo password. Privileged operations use non-interactive sudo (`sudo -n`) when `privilege.mode` is `sudo`.

## Kafka prerequisite check from the UI

When the backend/UI dispatches the existing `CHECK_PREREQUISITES` command for a host, the agent executes the checks locally on that managed VM and reports the complete result back through the existing task-result API. No SSH session from the backend is required.

The mandatory Kafka checks are:

- Open file soft and hard limits: both at least `1024000`
- `vm.swappiness`: exactly `0`
- Transparent Huge Pages: active policy is `never`
- SELinux: `Disabled` or `Permissive`
- Java: strictly `17.x`
- Time synchronization: `ntpd` or `chronyd` is active

The agent runs all six checks even when an earlier check fails, so the UI receives a complete host report in one click. The task status is `SUCCESS` only when all six mandatory checks pass; otherwise it is `FAILED` with the full pre-check output in `log_output`.

Kafka and ZooKeeper systemd units generated by this release also use `LimitNOFILE=1024000`, matching the host prerequisite.

See `docs/KAFKA_PREREQUISITES.md` for the exact UI-to-agent flow and expected output.


## Kafka artifacts uploaded through the UI

For `INSTALL_KAFKA`, the task may provide any of these compatible forms:

- `artifact_url` / `artifactUrl`
- `download_url` / `downloadUrl`
- `artifact_download_url` / `artifactDownloadUrl`
- `artifact_id` / `artifactId`
- the same values inside the task `parameters` map

If the task contains an artifact ID, the agent uses the ID as the canonical artifact identity. If the task contains only a direct artifact URL, the agent uses that URL first; this supports deployments where the control API and artifact repository are exposed on different ports. If a direct URL is unusable, compatible management-server fallbacks are attempted. When only an artifact ID is present, the agent tries the supported management-server artifact download routes and can follow a JSON metadata response that supplies the real download URL.

The downloaded file is written atomically and its SHA-256 is always calculated locally. If the backend supplies a checksum, the existing checksum-verification step still enforces it.

For JMX exporter setup, the air-gapped agent never contacts Maven Central. JMX is optional by default. Provide `jmx_artifact_id` or `jmx_artifact_url` from an artifact uploaded to Tantor, or pre-stage a valid exporter jar, when monitoring is required. Set `jmx_required=true` only when the deployment must fail if JMX is unavailable.

After extracting or reusing a Kafka archive, the agent normalizes the Kafka tree permissions and explicitly restores execute bits on `bin/*.sh`. This prevents uploaded archives with lost mode bits from failing later at `kafka-storage.sh` or service startup.

## Service operations

```bash
systemctl status tantor-agent --no-pager
journalctl -u tantor-agent -f
sudo systemctl restart tantor-agent
sudo systemctl stop tantor-agent
```

Validate configuration without starting the long-running loop:

```bash
/opt/tantor-agent/tantor-agent \
  -config /etc/tantor-agent/agent.yaml \
  -check-config
```

Run the bundled health check:

```bash
sudo ./scripts/health-check.sh
```

## Offline rebuild

The refactored agent has no third-party Go module dependencies. Build with module/network access explicitly disabled:

```bash
GOPROXY=off GOSUMDB=off go test ./...
VERSION=1.0.0-prod.11 ./scripts/build-release-offline.sh
```

The build script sets `CGO_ENABLED=0` and produces statically linked Linux binaries.

The runtime prerequisite remediation also never invokes `dnf`, `yum`, `apt`, `wget`, or another package installer/downloader. Required OS packages such as a time-sync daemon must be pre-staged through the client's approved offline process.

## Runtime overrides

The YAML file is the normal production configuration mechanism. Environment variables can override deployment-specific values without editing source, including:

```text
TANTOR_SERVER_URL
TANTOR_HOST_ID
TANTOR_AGENT_NAME
TANTOR_AUTH_MODE
TANTOR_AUTH_TOKEN_FILE
TANTOR_AUTH_USERNAME
TANTOR_AUTH_PASSWORD_FILE
TANTOR_TLS_CA_CERT
TANTOR_TLS_CERT_FILE
TANTOR_TLS_KEY_FILE
TANTOR_TLS_INSECURE_SKIP_VERIFY
TANTOR_DATA_DIR
TANTOR_LOG_DIR
TANTOR_ARTIFACTS_DIR
TANTOR_PRIVILEGE_MODE
TANTOR_SUDO_PATH
```

Do not put secrets directly in environment variables unless the client's security policy explicitly permits it. Credential files are preferred.

## Functionality retained

The dispatcher still supports the existing commands, including Kafka install/upgrade/configuration and service management, Connect, Schema Registry, ksqlDB, monitoring, parcel operations, prerequisite check/remediation, reboot scheduling, and KRaft/ZooKeeper quorum checks.

Kafka/service ports and product defaults that are part of the existing task contract remain unchanged. Deployment-specific values supplied by the backend/UI continue to override those defaults as before.

See `docs/SECURITY.md` before enabling privileged deployment actions in a production client environment.


## Air-gapped artifact flow (UI -> management server -> agent)

For Kafka installation, the normal production task should carry the artifact ID selected in the UI. The agent then downloads the archive from the configured management server with HTTP GET; it does not use SSH or SCP.

Canonical request:

```text
GET {server_url}/api/artifacts/{artifactId}/download
```

See `docs/ARTIFACT_FLOW.md` for the complete task and backend contract.
