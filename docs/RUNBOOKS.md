# Tantor Runbooks

## 1. Backup Scheduling (Production)

A backup script alone is not sufficient. In a Linux production environment, backups MUST be scheduled using an enterprise scheduler, Systemd Timer, or Cron.

**Example Systemd Timer (`tantor-backup.timer`):**
```ini
[Unit]
Description=Hourly Tantor Database Backup

[Timer]
OnCalendar=hourly
Persistent=true

[Install]
WantedBy=timers.target
```

**Example Systemd Service (`tantor-backup.service`):**
```ini
[Unit]
Description=Tantor Database Backup Service

[Service]
Type=oneshot
User=tantor
WorkingDirectory=/opt/tantor
ExecStart=/usr/bin/pwsh /opt/tantor/scripts/backup-postgres.ps1
```

**Operator Verification**:
Operators must verify the `backup_status.log` file in the backup destination and monitor the `TantorBackupStale` Prometheus alert.

## 2. Investigating High Queue Depth
If the `TantorHighQueueDepth` alert fires:
1. Verify if agents are connected: Check the UI or the `tantor_agents_connected` metric.
2. If agents are connected, check agent logs for network timeouts or internal crashes.
3. If agents are disconnected, investigate network segmentation or proxy authentication failures (M-07 rate limits or lockouts).
4. Do not manually restart the queue; it is durable. Restore agent connectivity and backpressure will naturally clear the queue.

## 3. Investigating Task Failures
If the `TantorTaskFailuresHigh` alert fires:
1. Check the Tantor UI Audit Log and Task History for explicit error messages.
2. Check the `tantor-server` logs. The M-06 structured error handling ensures all agent failures are bubbled up with a correlation ID.
3. Search the logs using the correlation ID. 

## 4. Rollback and Migration Classification

Do NOT assume database rollbacks are safe for every release. Flyway is used for schema management (H-01), meaning schema migrations are forward-only by default.

### Migration Classes:
* **Class A (Additive/Safe)**: Adds tables, non-nullable columns with defaults, or indices. 
  * **Rollback Strategy**: Previous application image ONLY. Database restore is NOT required.
* **Class B (Destructive/Stateful)**: Drops tables, columns, or changes types.
  * **Rollback Strategy**: Requires FULL Database Restore to the pre-deployment backup, OR a "Forward Fix" (deploying a new version that fixes the bug).

### Observable Rollback Gates (Canary):
A rollback must be initiated if any of the following observable signals breach thresholds during a deployment:
* API 5xx Error Rate > 1% over 5 minutes.
* `TantorHighQueueDepth` alert triggers and sustains for 10 minutes post-deployment.
* UI static asset load failures (HTTP 404).

## 5. Agent Outages
If `TantorAgentDisconnected` fires:
1. Validate mTLS certificates (C-06). Ensure the agent CA has not expired.
2. Check the Nginx Edge logs for rejected connections (CORS, Rate Limiting, invalid JWT).
3. Check the host VM for OOM kills.

## 6. Backup Failures
If `TantorBackupStale` fires:
1. Check the local filesystem capacity (`df -h`).
2. Check `deploy/backups/backup_status.log`.
3. Manually execute `./scripts/backup-postgres.ps1` to capture the failure output.
