# Tantor Agent 1.0.0-prod.11 — Final Production Hardening

This release consolidates the fixes validated during the successful air-gapped Kafka deployment from the 191 management plane to the 208 agent node.

## Final fixes included

- Dynamic management-server URL; no client-specific management IP is hard-coded.
- Route-aware host-IP discovery that excludes common virtual bridge addresses.
- Stable persistent host identity.
- Exact six Kafka prerequisite checks used by the UI.
- Air-gapped artifact flow: UI upload on 191, agent GET download to an agent-owned local staging directory, then local install on the target node.
- Artifact URL/ID compatibility, including direct artifact-service URLs on a separate port.
- JMX exporter is optional unless `jmx_required=true`; there is no public-internet fallback.
- KRaft metadata inspection and formatting use the privileged executor and do not depend on non-root access to `/data/kafka`.
- Kafka archive permissions are normalized after every extract/resume. All Kafka shell launchers under `bin/` are made readable/executable, preventing `kafka-storage.sh: Permission denied` when archive mode bits were lost.
- Installer repairs agent runtime/state directory ownership and restarts the service on upgrades.

## Deployment model

`191 UI/backend -> task poll by agent -> HTTP GET artifact from 191 artifact service -> local staging on target -> local Kafka install -> task status reported to 191`

No SSH, SCP, SFTP, shared filesystem, Maven Central, or runtime internet access is required.

