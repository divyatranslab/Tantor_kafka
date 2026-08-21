# Tantor Operational Targets and Disaster Recovery

This document defines the operational readiness model for the Tantor platform.

## 1. Service Level Objectives (SLO)

The Tantor platform is designed with a **Target Availability SLO of 99.9%** uptime per month. This is a target for the current single-region active deployment using container restart policies and health-checks.

## 2. Recovery Objectives

### Recovery Point Objective (RPO)
The maximum acceptable data loss in the event of a catastrophic failure:
* **PostgreSQL Application Data**: **≤ 1 Hour**. Logical backups (`pg_dump`) are executed hourly.
* **Artifact Data**: **Based on Artifact Storage Mechanism**. Artifacts are stored in `tantor_repo_data`. If lost, the RPO depends on whether the underlying storage provider (e.g., SAN, NAS) is replicated. If not, artifacts must be republished from the CI/CD pipeline since the last backup.
* **Audit Data**: **Determined by Database Retention + Export Durability**. Audit events are retained in the database and exported locally as append-only/tamper-evident records. If the local storage is lost, RPO is equivalent to the PostgreSQL RPO.

### Recovery Time Objective (RTO)
The maximum acceptable time to restore normal operations following a failure:
* **Service Recovery (Container Restart)**: < 5 Minutes (handled by Podman/Docker).
* **Full Disaster Recovery (DB Restore)**: **Target 1 Hour**. Actual measured RTO is documented during disaster recovery drills.

## 3. Backup Architecture and Durability

### Database Logical Backup
* **Mechanism**: Logical backup via `pg_dump`. 
* **Durability**: Backups are written to a persistent host-mounted location (`deploy/backups/`). **For real production, this destination must be an independent/off-host backup storage location (e.g., NFS, S3).**
* **Validation**: The backup script logs a timestamp, database identifier, performs a checksum validation, enforces retention, and records success/failure status.

### PITR Limitation
> [!WARNING]
> The current repository implementation provides **Logical Recovery (`pg_dump`)**, NOT True Point-In-Time Recovery (PITR).
> 
> True PITR (allowing sub-minute recovery via WAL archiving) requires additional infrastructure outside this standalone Compose stack, such as **pgBackRest**, **WAL-G**, or cloud-native object storage (S3). This standalone deployment relies exclusively on scheduled logical backups.

## 4. Immutable Audit Export
Audit logs are continuously exported to a local persistent volume as an **append-only, tamper-evident** JSONL log. 
Integrity protection is provided via a **chained hash** on each exported record. 
For true production immutable retention, a SIEM, WORM storage, or S3 Object-Lock destination must be provisioned. Passwords and secret values are strictly redacted.
