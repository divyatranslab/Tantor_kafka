-- Convert artifact metadata into an append-only action ledger.
ALTER TABLE artifact
    ADD COLUMN IF NOT EXISTS root_artifact_id UUID,
    ADD COLUMN IF NOT EXISTS action VARCHAR(40) NOT NULL DEFAULT 'UPLOAD',
    ADD COLUMN IF NOT EXISTS full_file_path VARCHAR(2048),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(128) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS downloaded_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS downloaded_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS verified_checksum BOOLEAN;

UPDATE artifact SET root_artifact_id = id WHERE root_artifact_id IS NULL;
ALTER TABLE artifact ALTER COLUMN root_artifact_id SET NOT NULL;

-- Preserve existing download history as ledger rows before dropping its table.
INSERT INTO artifact (
    id, root_artifact_id, action, service_type, version, file_name, relative_path,
    file_size_bytes, content_type, checksum_sha256, checksum_md5, status,
    created_by, created_at, updated_by, updated_at,
    downloaded_by, downloaded_at, verified_checksum,
    name, classifier, manifest, description, version_lock
)
SELECT
    gen_random_uuid(), a.id, 'DOWNLOAD', a.service_type, a.version, a.file_name, a.relative_path,
    a.file_size_bytes, a.content_type, a.checksum_sha256, a.checksum_md5, a.status,
    COALESCE(d.downloaded_by, 'system'), d.downloaded_at, COALESCE(d.downloaded_by, 'system'), d.downloaded_at,
    d.downloaded_by, d.downloaded_at, d.verified_checksum,
    a.name, a.classifier, a.manifest, a.description, 0
FROM artifact_download_log d
JOIN artifact a ON a.id = d.artifact_id;

DROP TABLE IF EXISTS artifact_download_log;

-- Preserve legacy duplicates as history, but allow only one canonical upload.
WITH duplicates AS (
    SELECT id
    FROM (
        SELECT id,
               row_number() OVER (PARTITION BY service_type, version ORDER BY created_at, id) AS version_rank,
               row_number() OVER (PARTITION BY checksum_sha256 ORDER BY created_at, id) AS checksum_rank
        FROM artifact
        WHERE action = 'UPLOAD'
    ) ranked
    WHERE version_rank > 1 OR checksum_rank > 1
)
UPDATE artifact SET action = 'LEGACY_DUPLICATE' WHERE id IN (SELECT id FROM duplicates);

DROP INDEX IF EXISTS ix_artifact_identity_history;
CREATE UNIQUE INDEX IF NOT EXISTS ux_artifact_service_version_upload
    ON artifact(service_type, version) WHERE action = 'UPLOAD';
CREATE UNIQUE INDEX IF NOT EXISTS ux_artifact_checksum_upload
    ON artifact(checksum_sha256) WHERE action = 'UPLOAD';
CREATE INDEX IF NOT EXISTS ix_artifact_root_created ON artifact(root_artifact_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_artifact_action_created ON artifact(action, created_at DESC);

ALTER TABLE artifact
    DROP COLUMN IF EXISTS name,
    DROP COLUMN IF EXISTS classifier,
    DROP COLUMN IF EXISTS manifest,
    DROP COLUMN IF EXISTS description,
    DROP COLUMN IF EXISTS version_lock;

-- Simplify the artifact audit table; created_at remains required for chronology.
DROP TRIGGER IF EXISTS trg_artifact_audit_immutable ON artifact_audit_log;
ALTER TABLE artifact_audit_log
    DROP COLUMN IF EXISTS old_value,
    DROP COLUMN IF EXISTS new_value,
    DROP COLUMN IF EXISTS ip_address,
    DROP COLUMN IF EXISTS source,
    DROP COLUMN IF EXISTS previous_hash,
    DROP COLUMN IF EXISTS record_hash;

DROP TRIGGER IF EXISTS trg_artifact_audit_immutable ON artifact_audit_log;
CREATE TRIGGER trg_artifact_audit_immutable
BEFORE UPDATE OR DELETE ON artifact_audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_artifact_audit_mutation();
