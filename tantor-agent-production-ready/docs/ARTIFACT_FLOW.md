# Air-gapped UI Artifact Deployment Flow

## Roles

- **Management/UI server (for example 191):** hosts the UI/control backend.
- **Artifact repository service:** may be exposed by the same host on a separate port.
- **Target VM (for example 208):** runs the Tantor agent and installs Kafka locally.

All addresses are runtime values supplied by configuration/task payloads; no client IP is compiled into the agent.

## End-to-end contract

### 1. Upload

The UI uploads a Kafka archive to the management environment. The backend creates a stable artifact record. The agent never needs the server-side filesystem path.

### 2. Deployment task

Preferred task form:

```json
{
  "task_id": "deployment-task-id",
  "command": "INSTALL_KAFKA",
  "artifactId": "artifact-uuid",
  "parameters": {
    "version": "4.1.0"
  }
}
```

For compatibility, the backend may instead provide a direct download URL, including a URL on a dedicated artifact-service port.

### 3. Agent GET download

- If `artifactId` is present, it is the canonical identity and the agent resolves it through supported artifact download routes on the configured management origin.
- If only an artifact URL is present, the agent performs HTTP GET against that URL first. This supports a control API such as `:8443` and an artifact service such as `:8081` on the same management host.
- If a backend-generated URL is unusable, compatible management-origin fallbacks may be attempted.

The HTTP response carries the artifact bytes. No SSH, SCP, SFTP, shared filesystem, or public internet is used.

### 4. Local staging

The artifact is staged only inside the configured agent-owned `paths.artifacts_dir` (default `/var/lib/tantor-agent/artifacts`). Backend-supplied absolute local paths outside that root are ignored.

The agent calculates SHA-256 locally while downloading and verifies a supplied checksum when present.

### 5. Local installation

The agent extracts Kafka locally, normalizes archive permissions (`a+rX` plus executable `bin/*.sh`), generates configuration, prepares KRaft/ZooKeeper state through the privileged executor, creates systemd units, starts Kafka, validates service/process/ports, and reports the task result.

KRaft metadata inspection/formatting does not depend on the non-root agent user being able to read `/data/kafka` directly.

### 6. JMX behavior

JMX exporter is optional by default in air-gapped deployments. No Maven/public repository fallback exists. To require JMX, upload a JMX exporter artifact and provide `jmx_artifact_id`/`jmx_artifact_url`, then set `jmx_required=true`.

### 7. Status reporting

The agent sends task progress/final status back through the existing control-plane task-result API so the UI can show `SUCCESS` or `FAILED`.
