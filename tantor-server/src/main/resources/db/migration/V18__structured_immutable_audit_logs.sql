ALTER TABLE activity_logs
    ADD COLUMN event_type VARCHAR(80),
    ADD COLUMN action VARCHAR(80),
    ADD COLUMN actor VARCHAR(255),
    ADD COLUMN resource_type VARCHAR(80),
    ADD COLUMN resource_id VARCHAR(255),
    ADD COLUMN old_value TEXT,
    ADD COLUMN new_value TEXT,
    ADD COLUMN ip_address VARCHAR(64),
    ADD COLUMN event_status VARCHAR(40),
    ADD COLUMN approval_status VARCHAR(40),
    ADD COLUMN metadata TEXT;

CREATE INDEX idx_activity_logs_created_at ON activity_logs (created_at DESC);
CREATE INDEX idx_activity_logs_event_type ON activity_logs (event_type);
CREATE INDEX idx_activity_logs_resource ON activity_logs (resource_type, resource_id);

-- Audit records are append-only. Even direct SQL updates/deletes are rejected.
CREATE OR REPLACE FUNCTION prevent_activity_log_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'activity_logs is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER activity_logs_immutable
BEFORE UPDATE OR DELETE ON activity_logs
FOR EACH ROW EXECUTE FUNCTION prevent_activity_log_mutation();






