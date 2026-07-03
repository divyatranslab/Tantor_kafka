CREATE TABLE IF NOT EXISTS artifact_audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor VARCHAR(255) NOT NULL,
    event_category VARCHAR(80) NOT NULL,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(80) NOT NULL,
    resource_id VARCHAR(255),
    status VARCHAR(40) NOT NULL,
    old_value JSONB,
    new_value JSONB,
    details JSONB,
    ip_address VARCHAR(64),
    source VARCHAR(80) NOT NULL DEFAULT 'ARTIFACT_REPOSITORY',
    previous_hash CHAR(64),
    record_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_artifact_audit_created ON artifact_audit_log(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_artifact_audit_action ON artifact_audit_log(action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_artifact_audit_status ON artifact_audit_log(status, created_at DESC);

CREATE OR REPLACE FUNCTION prevent_artifact_audit_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'artifact_audit_log is append-only; % is forbidden', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_artifact_audit_immutable ON artifact_audit_log;
CREATE TRIGGER trg_artifact_audit_immutable
BEFORE UPDATE OR DELETE ON artifact_audit_log
FOR EACH ROW EXECUTE FUNCTION prevent_artifact_audit_mutation();
