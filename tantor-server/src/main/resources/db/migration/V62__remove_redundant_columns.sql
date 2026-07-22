-- Remove redundant user column from kf_clusters
ALTER TABLE kf_clusters DROP COLUMN IF EXISTS "user";

-- Remove redundant actor_user and user_id columns from kf_cluster_audit_log
ALTER TABLE kf_cluster_audit_log DROP COLUMN IF EXISTS actor_user;
ALTER TABLE kf_cluster_audit_log DROP COLUMN IF EXISTS user_id;
