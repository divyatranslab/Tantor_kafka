-- Upload/delete events are immutable ledger rows. Uniqueness therefore has to
-- apply to artifact roots whose latest lifecycle has not been deleted, rather
-- than to every historical UPLOAD row.
DROP INDEX IF EXISTS ux_artifact_service_version_upload;
DROP INDEX IF EXISTS ux_artifact_checksum_upload;

CREATE INDEX IF NOT EXISTS ix_artifact_service_version_upload
    ON artifact(service_type, version) WHERE action = 'UPLOAD';
CREATE INDEX IF NOT EXISTS ix_artifact_checksum_upload
    ON artifact(checksum_sha256) WHERE action = 'UPLOAD';
