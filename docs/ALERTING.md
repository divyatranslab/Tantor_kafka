# Tantor Alerting Definitions

This document defines the Prometheus alerting rules for the Tantor platform. These rules must be applied to the Prometheus instance monitoring Tantor.

## Alerting Rules

### 1. Task Queue Depth
* **Alert**: `TantorHighQueueDepth`
* **Condition**: `tantor_tasks_queued > 50`
* **Evaluation Period**: `5m`
* **Severity**: `warning`
* **Description**: The number of pending tasks is high, indicating agents are not processing them quickly enough or are disconnected.
* **Runbook**: [RUNBOOKS.md#investigating-high-queue-depth](RUNBOOKS.md#investigating-high-queue-depth)

### 2. Task Failures
* **Alert**: `TantorTaskFailuresHigh`
* **Condition**: `rate(tantor_tasks_failed[5m]) > 1`
* **Evaluation Period**: `2m`
* **Severity**: `critical`
* **Description**: Tasks are failing permanently at a high rate.
* **Runbook**: [RUNBOOKS.md#investigating-task-failures](RUNBOOKS.md#investigating-task-failures)

### 3. Agent Disconnection
* **Alert**: `TantorAgentDisconnected`
* **Condition**: `tantor_agents_connected < 1` (or based on expected host count)
* **Evaluation Period**: `2m`
* **Severity**: `critical`
* **Description**: No agents are connected to the management server.
* **Runbook**: [RUNBOOKS.md#agent-outages](RUNBOOKS.md#agent-outages)

### 4. Backup Staleness
* **Alert**: `TantorBackupStale`
* **Condition**: `time() - tantor_last_backup_timestamp_seconds > 7200`
* **Evaluation Period**: `5m`
* **Severity**: `critical`
* **Description**: No successful logical backup has occurred in the last 2 hours. RPO is at risk.
* **Runbook**: [RUNBOOKS.md#backup-failures](RUNBOOKS.md#backup-failures)

## Implementing Rules

To apply these rules, add them to your `prometheus_rules.yml`:
```yaml
groups:
  - name: tantor.rules
    rules:
      - alert: TantorHighQueueDepth
        expr: tantor_tasks_queued > 50
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Tantor task queue is backing up"
```
