-- V2__update_clusters_schema.sql

-- Update clusters table to match Cluster.java
ALTER TABLE clusters DROP COLUMN cluster_type;
ALTER TABLE clusters DROP COLUMN status;
ALTER TABLE clusters ADD COLUMN kafka_version VARCHAR(50) NOT NULL DEFAULT 'unknown';
ALTER TABLE clusters ADD COLUMN mode VARCHAR(50);
ALTER TABLE clusters ADD COLUMN environment VARCHAR(50);
ALTER TABLE clusters ADD COLUMN config_json TEXT;

-- Rename services to cluster_services and update columns to match ClusterServiceAssignment.java
ALTER TABLE services RENAME TO cluster_services;
ALTER TABLE cluster_services DROP COLUMN service_type;
ALTER TABLE cluster_services DROP COLUMN status;
ALTER TABLE cluster_services DROP COLUMN config_overrides;
ALTER TABLE cluster_services DROP COLUMN created_at;
ALTER TABLE cluster_services DROP COLUMN updated_at;
ALTER TABLE cluster_services ADD COLUMN role VARCHAR(50) NOT NULL DEFAULT 'broker';

-- Change cluster_services id from UUID to VARCHAR(36)
ALTER TABLE cluster_services ALTER COLUMN id TYPE VARCHAR(36);
