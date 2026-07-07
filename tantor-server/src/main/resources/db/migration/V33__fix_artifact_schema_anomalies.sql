ALTER TABLE artifact ADD COLUMN IF NOT EXISTS root_artifact_id UUID;
ALTER TABLE artifact ADD COLUMN IF NOT EXISTS downloaded_by VARCHAR(128);
ALTER TABLE artifact_audit_log DROP COLUMN IF EXISTS record_hash;
