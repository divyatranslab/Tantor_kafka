-- Every upload is an immutable artifact entry. The UUID-specific storage path
-- prevents a later upload of the same service/version from replacing its file.
DROP INDEX IF EXISTS ux_artifact_identity;

CREATE INDEX ix_artifact_identity_history
    ON artifact (service_type, version, COALESCE(classifier, ''), created_at DESC);
