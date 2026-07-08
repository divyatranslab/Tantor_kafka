
--- V1__init_schema.sql ---
-- V1__init_schema.sql
-- Tantor Platform Database Schema (PostgreSQL 16)

-- 1. Users & RBAC
CREATE TABLE roles (
    id SERIAL PRIMARY KEY,
    name VARCHAR(50) UNIQUE NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE permissions (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    description VARCHAR(255)
);

CREATE TABLE role_permissions (
    role_id INT REFERENCES roles(id) ON DELETE CASCADE,
    permission_id INT REFERENCES permissions(id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    role_id INT REFERENCES roles(id),
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 2. Infrastructure Inventory
CREATE TABLE clusters (
    id UUID PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    cluster_type VARCHAR(50) NOT NULL, -- e.g., KAFKA, CONNECT, SCHEMA_REGISTRY
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE hosts (
    id VARCHAR(100) PRIMARY KEY, -- Agent host_id
    hostname VARCHAR(255) NOT NULL,
    ip_addresses JSONB,
    os_details VARCHAR(255),
    agent_version VARCHAR(50),
    status VARCHAR(50) NOT NULL, -- ONLINE, OFFLINE, ERROR
    last_heartbeat TIMESTAMP WITH TIME ZONE,
    cpu_usage_pct DOUBLE PRECISION,
    mem_total_mb BIGINT,
    mem_used_mb BIGINT,
    disk_total_gb BIGINT,
    disk_used_gb BIGINT,
    java_version VARCHAR(100),
    cluster_id UUID REFERENCES clusters(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 3. Services & Deployments
CREATE TABLE services (
    id UUID PRIMARY KEY,
    cluster_id UUID REFERENCES clusters(id) ON DELETE CASCADE,
    host_id VARCHAR(100) REFERENCES hosts(id) ON DELETE RESTRICT,
    service_type VARCHAR(50) NOT NULL, -- BROKER, CONTROLLER, WORKER
    node_id INT, -- e.g., Kafka broker.id
    status VARCHAR(50) NOT NULL,
    config_overrides JSONB,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 4. Tasks & Audit
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    host_id VARCHAR(100) REFERENCES hosts(id) ON DELETE CASCADE,
    command VARCHAR(100) NOT NULL,
    parameters JSONB,
    artifact_url VARCHAR(255),
    checksum VARCHAR(255),
    status VARCHAR(50) NOT NULL, -- PENDING, IN_PROGRESS, SUCCESS, FAILED
    log_output TEXT,
    error_msg TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id VARCHAR(100),
    details JSONB,
    ip_address VARCHAR(45),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_hosts_cluster ON hosts(cluster_id);
CREATE INDEX idx_services_cluster ON services(cluster_id);
CREATE INDEX idx_tasks_host ON tasks(host_id);
CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

-- Initial Data
INSERT INTO roles (name, description) VALUES ('ADMIN', 'Administrator with full access');
INSERT INTO roles (name, description) VALUES ('OPERATOR', 'Operator with deployment access');
INSERT INTO roles (name, description) VALUES ('VIEWER', 'Read-only access');

-- Note: Default admin user password should be populated via application logic or a secure seed

--- V2__update_clusters_schema.sql ---
-- V2__update_clusters_schema.sql

-- Update clusters table to match Cluster.java
ALTER TABLE clusters DROP COLUMN IF EXISTS cluster_type;
ALTER TABLE clusters DROP COLUMN IF EXISTS status;
ALTER TABLE clusters ADD COLUMN IF NOT EXISTS kafka_version VARCHAR(50) NOT NULL DEFAULT 'unknown';
ALTER TABLE clusters ADD COLUMN IF NOT EXISTS mode VARCHAR(50);
ALTER TABLE clusters ADD COLUMN IF NOT EXISTS environment VARCHAR(50);
ALTER TABLE clusters ADD COLUMN IF NOT EXISTS config_json TEXT;

-- Rename services to cluster_services and update columns to match ClusterServiceAssignment.java
ALTER TABLE IF EXISTS services RENAME TO cluster_services;
ALTER TABLE cluster_services DROP COLUMN IF EXISTS service_type;
ALTER TABLE cluster_services DROP COLUMN IF EXISTS status;
ALTER TABLE cluster_services DROP COLUMN IF EXISTS config_overrides;
ALTER TABLE cluster_services DROP COLUMN IF EXISTS created_at;
ALTER TABLE cluster_services DROP COLUMN IF EXISTS updated_at;
ALTER TABLE cluster_services ADD COLUMN IF NOT EXISTS role VARCHAR(50) NOT NULL DEFAULT 'broker';

-- Change cluster_services id from UUID to VARCHAR(36)
ALTER TABLE cluster_services ALTER COLUMN id TYPE VARCHAR(36) USING id::VARCHAR;

--- V3__revert_cluster_services_id_to_uuid.sql ---
-- V3__revert_cluster_services_id_to_uuid.sql
-- Revert the ID type back to UUID because Java uses UUID.
ALTER TABLE cluster_services ALTER COLUMN id TYPE UUID USING id::uuid;

--- V4__add_bootstrap_servers.sql ---
ALTER TABLE clusters ADD COLUMN bootstrap_servers VARCHAR(255);
ALTER TABLE clusters ADD COLUMN external_broker_hosts_json TEXT;

--- V5__activity_and_alerts.sql ---
CREATE TABLE activity_logs (
    id UUID PRIMARY KEY,
    level VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    cluster_id UUID,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE alerts (
    id UUID PRIMARY KEY,
    severity VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    cluster_id UUID,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP WITH TIME ZONE
);

--- V6__Add_cluster_status.sql ---
ALTER TABLE clusters
ADD COLUMN status VARCHAR(50) DEFAULT 'PENDING',
ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;

--- V7__Add_task_cluster_id.sql ---
ALTER TABLE tasks
    ADD COLUMN cluster_id UUID REFERENCES clusters(id) ON DELETE SET NULL;

UPDATE tasks t
SET cluster_id = h.cluster_id
FROM hosts h
WHERE t.host_id = h.id
  AND t.cluster_id IS NULL;

CREATE INDEX idx_tasks_cluster ON tasks(cluster_id);

ALTER TABLE clusters DROP CONSTRAINT IF EXISTS clusters_name_key;

CREATE UNIQUE INDEX ux_clusters_active_name
    ON clusters(name)
    WHERE COALESCE(status, 'PENDING') <> 'DELETED';

--- V8__parcel_lifecycle.sql ---
CREATE TABLE host_parcels (
    id UUID PRIMARY KEY,
    host_id VARCHAR(100) NOT NULL REFERENCES hosts(id) ON DELETE CASCADE,
    artifact_id UUID NOT NULL,
    service_type VARCHAR(50) NOT NULL,
    version VARCHAR(80) NOT NULL,
    file_name VARCHAR(512),
    artifact_url TEXT,
    checksum VARCHAR(255),
    parcel_dir VARCHAR(1024),
    status VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    last_task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    error_msg TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_host_parcel_artifact UNIQUE (host_id, artifact_id)
);

CREATE INDEX idx_host_parcels_host ON host_parcels(host_id);
CREATE INDEX idx_host_parcels_artifact ON host_parcels(artifact_id);
CREATE INDEX idx_host_parcels_status ON host_parcels(status);
CREATE INDEX idx_host_parcels_active ON host_parcels(host_id, service_type, active);

--- V9__add_service_config_json.sql ---
ALTER TABLE cluster_services ADD COLUMN IF NOT EXISTS config_json TEXT;

--- V10__add_jobs_table.sql ---
CREATE TABLE jobs (
    id VARCHAR(36) PRIMARY KEY,
    type VARCHAR(50) NOT NULL,
    status VARCHAR(50) NOT NULL,
    requested_by VARCHAR(255),
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    payload JSONB,
    progress JSONB,
    logs TEXT,
    retry_count INT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

--- V11__add_durable_job_steps.sql ---
ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS rollback_supported BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE job_steps (
    id UUID PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    step_order INT NOT NULL,
    name VARCHAR(255) NOT NULL,
    target_id VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    payload JSONB,
    agent_task_id UUID REFERENCES tasks(id) ON DELETE SET NULL,
    logs TEXT,
    retry_count INT NOT NULL DEFAULT 0,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_job_steps_order UNIQUE (job_id, step_order)
);

CREATE INDEX idx_job_steps_job ON job_steps(job_id, step_order);
CREATE INDEX idx_job_steps_status ON job_steps(status);

--- V12__add_active_job_resource_lock.sql ---
ALTER TABLE jobs
    ADD COLUMN IF NOT EXISTS resource_key VARCHAR(255);

CREATE UNIQUE INDEX uq_jobs_active_resource
    ON jobs(resource_key)
    WHERE resource_key IS NOT NULL
      AND status IN ('PENDING', 'IN_PROGRESS', 'ROLLBACK_PENDING', 'ROLLING_BACK');

--- V13__add_config_versions.sql ---
CREATE TABLE config_versions (
    id UUID PRIMARY KEY,
    cluster_id UUID NOT NULL REFERENCES clusters(id) ON DELETE CASCADE,
    service_id UUID REFERENCES cluster_services(id) ON DELETE SET NULL,
    host_id VARCHAR(100) NOT NULL,
    component VARCHAR(100) NOT NULL,
    config_file_name VARCHAR(500) NOT NULL,
    config_version INT NOT NULL,
    old_config JSONB NOT NULL,
    new_config JSONB NOT NULL,
    status VARCHAR(50) NOT NULL,
    approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    validation_result JSONB NOT NULL DEFAULT '{}',
    created_by VARCHAR(255) NOT NULL,
    approved_by VARCHAR(255),
    job_id VARCHAR(36) REFERENCES jobs(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_at TIMESTAMP WITH TIME ZONE,
    applied_at TIMESTAMP WITH TIME ZONE,
    rollback_version INT,
    CONSTRAINT uq_config_version UNIQUE (cluster_id, host_id, component, config_file_name, config_version)
);

CREATE INDEX idx_config_versions_target
    ON config_versions(cluster_id, host_id, component, config_file_name, config_version DESC);

CREATE INDEX idx_config_versions_status
    ON config_versions(status, created_at);

--- V14__add_ldap_config.sql ---
CREATE TABLE IF NOT EXISTS ldap_configs (
    id UUID PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    server_url VARCHAR(500),
    use_ssl BOOLEAN NOT NULL DEFAULT FALSE,
    tls_validate_cert BOOLEAN NOT NULL DEFAULT TRUE,
    tls_ca_cert TEXT,
    bind_dn VARCHAR(500),
    encrypted_bind_password VARCHAR(1000),
    user_search_base VARCHAR(500),
    user_search_filter VARCHAR(500) DEFAULT '(sAMAccountName={username})',
    group_search_base VARCHAR(500),
    admin_group_dn VARCHAR(500),
    monitor_group_dn VARCHAR(500),
    default_role VARCHAR(20) DEFAULT 'monitor',
    connection_timeout INT DEFAULT 10
);

ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_source VARCHAR(20) DEFAULT 'local';
ALTER TABLE users ADD COLUMN IF NOT EXISTS ldap_dn VARCHAR(500);

--- V15__update_user_role.sql ---
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_id_fkey;
ALTER TABLE users DROP COLUMN IF EXISTS role_id;
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(20) DEFAULT 'monitor';

DROP TABLE IF EXISTS role_permissions CASCADE;
DROP TABLE IF EXISTS permissions CASCADE;
DROP TABLE IF EXISTS roles CASCADE;

ALTER TABLE users ADD COLUMN IF NOT EXISTS auth_source VARCHAR(20) DEFAULT 'local';
ALTER TABLE users ADD COLUMN IF NOT EXISTS ldap_dn VARCHAR(500);

INSERT INTO users (id, username, password_hash, role, auth_source, is_active)
VALUES ('00000000-0000-0000-0000-000000000000', 'admin', '$2a$10$7Z2P.M8h.rZ2/t4H4y.4K.nS6q.rZ2/t4H4y.4K.nS6q.rZ2/t4H', 'admin', 'local', true)
ON CONFLICT (username) DO NOTHING;

--- V16__add_task_step_tracking.sql ---
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS current_step VARCHAR(255);
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS failed_reason TEXT;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS step_logs JSONB;

--- V17__immutable_audit_ledger.sql ---
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

ALTER TABLE audit_logs
    ADD COLUMN IF NOT EXISTS cluster_id UUID,
    ADD COLUMN IF NOT EXISTS actor VARCHAR(255) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS event_category VARCHAR(80) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN IF NOT EXISTS status VARCHAR(40) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN IF NOT EXISTS old_value JSONB,
    ADD COLUMN IF NOT EXISTS new_value JSONB,
    ADD COLUMN IF NOT EXISTS approval JSONB,
    ADD COLUMN IF NOT EXISTS source VARCHAR(80) NOT NULL DEFAULT 'MANAGEMENT_SERVER',
    ADD COLUMN IF NOT EXISTS request_id VARCHAR(100),
    ADD COLUMN IF NOT EXISTS previous_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS record_hash CHAR(64);

UPDATE audit_logs
SET source = 'LEGACY',
    record_hash = md5(id::text || created_at::text) || md5(created_at::text || id::text)
WHERE record_hash IS NULL;

INSERT INTO audit_logs (
    id, action, entity_type, entity_id, details, cluster_id, actor,
    event_category, status, source, created_at, record_hash
)
SELECT gen_random_uuid(), 'LEGACY_ACTIVITY', 'CLUSTER', cluster_id::text,
       jsonb_build_object('level', level, 'message', message), cluster_id,
       'system', 'SYSTEM',
       CASE WHEN level IN ('ERROR', 'CRITICAL') THEN 'FAILED' ELSE 'SUCCESS' END,
       'LEGACY', created_at,
       md5(id::text || created_at::text) || md5(created_at::text || id::text)
FROM activity_logs;

ALTER TABLE audit_logs ALTER COLUMN record_hash SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_audit_category_created ON audit_logs(event_category, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_actor_created ON audit_logs(actor, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_resource ON audit_logs(entity_type, entity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_status_created ON audit_logs(status, created_at DESC);

CREATE OR REPLACE FUNCTION prevent_audit_log_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only; % is forbidden', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_logs_immutable ON audit_logs;
CREATE TRIGGER trg_audit_logs_immutable
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE PROCEDURE prevent_audit_log_mutation();

--- V19__parcel_distribution_details.sql ---
ALTER TABLE host_parcels ADD COLUMN host_ip VARCHAR(45);


--- V20__cluster_identity_and_paths.sql ---
ALTER TABLE clusters RENAME COLUMN name TO cluster_name;
ALTER TABLE clusters ADD COLUMN origin_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE clusters ADD COLUMN kafka_cluster_id VARCHAR(255);
ALTER TABLE clusters ADD COLUMN install_directory VARCHAR(1024);
ALTER TABLE clusters ADD COLUMN config_directory VARCHAR(1024);
ALTER TABLE clusters ADD COLUMN data_directory VARCHAR(1024);
ALTER TABLE clusters ADD COLUMN log_directory VARCHAR(1024);

UPDATE clusters
SET origin_type = CASE WHEN UPPER(COALESCE(mode, '')) = 'EXTERNAL' THEN 'EXTERNAL' ELSE 'INTERNAL' END;

UPDATE clusters
SET kafka_cluster_id = NULLIF(config_json::jsonb ->> 'kafkaClusterId', '')
WHERE UPPER(COALESCE(mode, '')) = 'EXTERNAL'
  AND config_json IS NOT NULL
  AND config_json ~ '^\\s*\\{';

CREATE INDEX idx_clusters_origin_type ON clusters(origin_type);
CREATE UNIQUE INDEX uq_clusters_kafka_cluster_id
    ON clusters(kafka_cluster_id) WHERE kafka_cluster_id IS NOT NULL AND kafka_cluster_id <> '';


--- V21__expand_activity_audit_columns.sql ---
-- Activity audit details were added to the entity after the original V5 table.
-- Keep V5 immutable and evolve the existing table additively.
ALTER TABLE activity_logs
    ADD COLUMN IF NOT EXISTS event_type VARCHAR(80),
    ADD COLUMN IF NOT EXISTS action VARCHAR(100),
    ADD COLUMN IF NOT EXISTS actor VARCHAR(255) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS resource_type VARCHAR(80),
    ADD COLUMN IF NOT EXISTS resource_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS old_value TEXT,
    ADD COLUMN IF NOT EXISTS new_value TEXT,
    ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45),
    ADD COLUMN IF NOT EXISTS event_status VARCHAR(40) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN IF NOT EXISTS approval_status VARCHAR(40),
    ADD COLUMN IF NOT EXISTS metadata TEXT;

UPDATE activity_logs
SET event_type = COALESCE(event_type, 'LEGACY_ACTIVITY'),
    action = COALESCE(action, 'RECORDED'),
    actor = COALESCE(actor, 'system'),
    event_status = COALESCE(event_status,
        CASE WHEN UPPER(level) IN ('ERROR', 'CRITICAL') THEN 'FAILED' ELSE 'SUCCESS' END)
WHERE event_type IS NULL OR action IS NULL OR actor IS NULL OR event_status IS NULL;

CREATE INDEX IF NOT EXISTS idx_activity_event_created
    ON activity_logs(event_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_activity_resource_created
    ON activity_logs(resource_type, resource_id, created_at DESC);

--- V22__host_agent_details.sql ---
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS host_number BIGINT GENERATED ALWAYS AS IDENTITY;
ALTER TABLE hosts DROP CONSTRAINT IF EXISTS uq_hosts_host_number;
ALTER TABLE hosts ADD CONSTRAINT uq_hosts_host_number UNIQUE (host_number);
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS agent_name VARCHAR(255);
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS agent_path VARCHAR(1024);
ALTER TABLE hosts ADD COLUMN IF NOT EXISTS agent_status VARCHAR(50) NOT NULL DEFAULT 'OFFLINE';

--- V23__append_only_host_parcels.sql ---
ALTER TABLE host_parcels
    DROP CONSTRAINT IF EXISTS uq_host_parcel_artifact,
    DROP COLUMN IF EXISTS artifact_url,
    ADD COLUMN IF NOT EXISTS action VARCHAR(40) NOT NULL DEFAULT 'DISTRIBUTE',
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(128) NOT NULL DEFAULT 'system';

UPDATE host_parcels
SET action = CASE
    WHEN status IN ('ACTIVE', 'ACTIVATING') THEN 'ACTIVATE'
    WHEN status IN ('DEACTIVATED', 'DEACTIVATING') THEN 'DEACTIVATE'
    WHEN status IN ('REMOVED', 'REMOVING') THEN 'REMOVE'
    ELSE 'DISTRIBUTE'
END;

CREATE INDEX IF NOT EXISTS idx_host_parcels_latest
    ON host_parcels(host_id, artifact_id, created_at DESC, id DESC);


--- V24__add_uuid_agent_id.sql ---
ALTER TABLE hosts DROP COLUMN IF EXISTS agent_name;

ALTER TABLE hosts ADD COLUMN IF NOT EXISTS agent_id UUID;
ALTER TABLE tasks ADD COLUMN IF NOT EXISTS agent_id UUID;
ALTER TABLE host_parcels ADD COLUMN IF NOT EXISTS agent_id UUID;
ALTER TABLE cluster_services ADD COLUMN IF NOT EXISTS agent_id UUID;

--- V25__refactor_agent_id_and_name.sql ---
-- Drop the auto-increment host_number sequence and column
ALTER TABLE hosts DROP COLUMN host_number;

-- Drop the UUID agent_id columns since we will now use 'id' for the UUID
ALTER TABLE hosts DROP COLUMN agent_id;
ALTER TABLE tasks DROP COLUMN agent_id;
ALTER TABLE host_parcels DROP COLUMN agent_id;
ALTER TABLE cluster_services DROP COLUMN agent_id;

-- Add agent_name back as a regular column
ALTER TABLE hosts ADD COLUMN agent_name VARCHAR(255);

-- Wipe the old data so the agent can re-register with its new UUID Primary Key
TRUNCATE TABLE hosts CASCADE;

--- V26__restore_agent_references.sql ---
-- V25 removed these columns while the corresponding entities and runtime
-- relationships still use them. Restore them without modifying an already
-- applied Flyway migration.
ALTER TABLE tasks
    ADD COLUMN IF NOT EXISTS agent_id UUID;

ALTER TABLE host_parcels
    ADD COLUMN IF NOT EXISTS agent_id UUID;

ALTER TABLE cluster_services
    ADD COLUMN IF NOT EXISTS agent_id UUID;

CREATE INDEX IF NOT EXISTS idx_tasks_agent_id ON tasks(agent_id);
CREATE INDEX IF NOT EXISTS idx_host_parcels_agent_id ON host_parcels(agent_id);
CREATE INDEX IF NOT EXISTS idx_cluster_services_agent_id ON cluster_services(agent_id);

--- V27__remove_audit_old_new_values.sql ---
ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS old_value,
    DROP COLUMN IF EXISTS new_value;

--- V28__simplify_audit_and_expand_cluster_inventory.sql ---
ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS user_id,
    DROP COLUMN IF EXISTS previous_hash,
    DROP COLUMN IF EXISTS record_hash;

ALTER TABLE clusters
    ADD COLUMN IF NOT EXISTS node_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(128) NOT NULL DEFAULT 'system';

UPDATE clusters c
SET node_ids = COALESCE((
    SELECT jsonb_agg(nodes.node_id ORDER BY nodes.node_id)
    FROM (
        SELECT DISTINCT cs.node_id
        FROM cluster_services cs
        WHERE cs.cluster_id = c.id AND cs.node_id IS NOT NULL
    ) nodes
), '[]'::jsonb);

CREATE OR REPLACE FUNCTION refresh_cluster_node_ids()
RETURNS trigger AS $$
DECLARE
    affected_cluster_id UUID;
BEGIN
    affected_cluster_id := CASE WHEN TG_OP = 'DELETE' THEN OLD.cluster_id ELSE NEW.cluster_id END;
    UPDATE clusters c
    SET node_ids = COALESCE((
            SELECT jsonb_agg(nodes.node_id ORDER BY nodes.node_id)
            FROM (
                SELECT DISTINCT cs.node_id
                FROM cluster_services cs
                WHERE cs.cluster_id = affected_cluster_id AND cs.node_id IS NOT NULL
            ) nodes
        ), '[]'::jsonb),
        updated_by = 'system',
        updated_at = CURRENT_TIMESTAMP
    WHERE c.id = affected_cluster_id;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_refresh_cluster_node_ids ON cluster_services;
CREATE TRIGGER trg_refresh_cluster_node_ids
AFTER INSERT OR UPDATE OR DELETE ON cluster_services
FOR EACH ROW EXECUTE PROCEDURE refresh_cluster_node_ids();

UPDATE hosts SET status = 'OCCUPIED' WHERE status = 'UNAVAILABLE';

--- V29__persist_runtime_alerts.sql ---
ALTER TABLE alerts
    ADD COLUMN IF NOT EXISTS alert_key VARCHAR(255),
    ADD COLUMN IF NOT EXISTS host_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS source VARCHAR(80) NOT NULL DEFAULT 'stored',
    ADD COLUMN IF NOT EXISTS error_log TEXT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP;

CREATE UNIQUE INDEX IF NOT EXISTS uq_alerts_alert_key
    ON alerts(alert_key) WHERE alert_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_alerts_status_source
    ON alerts(status, source, updated_at DESC);

--- V30__merge_artifact_schema.sql ---
-- Merge Artifact Repository schema into Tantor Server schema management.
-- This script safely creates the final tables if they do not already exist,
-- preventing failures on existing environments where the tantor-artifact-repository
-- had already applied its own flyway migrations.

DO $$ 
BEGIN
    IF NOT EXISTS (SELECT FROM pg_tables WHERE schemaname = 'public' AND tablename  = 'artifact') THEN
        
        CREATE TABLE artifact (
            id                UUID         NOT NULL DEFAULT gen_random_uuid(),
            root_artifact_id  UUID         NOT NULL,
            action            VARCHAR(40)  NOT NULL DEFAULT 'UPLOAD',
            service_type      VARCHAR(40)  NOT NULL,
            version           VARCHAR(80)  NOT NULL,
            file_name         VARCHAR(512) NOT NULL,
            relative_path     VARCHAR(1024) NOT NULL,
            full_file_path    VARCHAR(2048),
            file_size_bytes   BIGINT       NOT NULL,
            content_type      VARCHAR(128) NOT NULL DEFAULT 'application/gzip',
            checksum_sha256   CHAR(64)     NOT NULL,
            checksum_md5      CHAR(32),
            status            VARCHAR(32)  NOT NULL DEFAULT 'UPLOADING',
            created_by        VARCHAR(128) NOT NULL DEFAULT 'system',
            created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
            updated_by        VARCHAR(128) NOT NULL DEFAULT 'system',
            updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
            downloaded_by     VARCHAR(128),
            downloaded_at     TIMESTAMPTZ,
            verified_checksum BOOLEAN,
            CONSTRAINT pk_artifact PRIMARY KEY (id),
            CONSTRAINT ck_artifact_service_type CHECK (
                service_type IN ('KAFKA','KAFKA_CONNECT','SCHEMA_REGISTRY','KSQLDB',
                                 'CRUISE_CONTROL','PROMETHEUS','GRAFANA')
            ),
            CONSTRAINT ck_artifact_status CHECK (
                status IN ('UPLOADING','AVAILABLE','CORRUPTED','QUARANTINED','DELETED', 'LEGACY_DUPLICATE')
            )
        );
        
        CREATE INDEX ix_artifact_service_type ON artifact (service_type);
        CREATE INDEX ix_artifact_status       ON artifact (status);
        CREATE INDEX ix_artifact_sha256       ON artifact (checksum_sha256);
        CREATE INDEX ix_artifact_service_version_upload ON artifact(service_type, version) WHERE action = 'UPLOAD';
        CREATE INDEX ix_artifact_checksum_upload ON artifact(checksum_sha256) WHERE action = 'UPLOAD';
        CREATE INDEX ix_artifact_root_created ON artifact(root_artifact_id, created_at DESC);
        CREATE INDEX ix_artifact_action_created ON artifact(action, created_at DESC);
        
        CREATE TABLE artifact_audit_log (
            id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
            actor VARCHAR(255) NOT NULL,
            event_category VARCHAR(80) NOT NULL,
            action VARCHAR(100) NOT NULL,
            resource_type VARCHAR(80) NOT NULL,
            resource_id VARCHAR(255),
            status VARCHAR(40) NOT NULL,
            details JSONB,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        
        CREATE INDEX idx_artifact_audit_created ON artifact_audit_log(created_at DESC);
        CREATE INDEX idx_artifact_audit_action ON artifact_audit_log(action, created_at DESC);
        CREATE INDEX idx_artifact_audit_status ON artifact_audit_log(status, created_at DESC);
        
    END IF;
END $$;

-- Ensure the trigger function exists and the trigger is applied in either case (new DB or existing DB).
CREATE OR REPLACE FUNCTION prevent_artifact_audit_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'artifact_audit_log is append-only; % is forbidden', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_artifact_audit_immutable ON artifact_audit_log;
CREATE TRIGGER trg_artifact_audit_immutable
BEFORE UPDATE OR DELETE ON artifact_audit_log
FOR EACH ROW EXECUTE PROCEDURE prevent_artifact_audit_mutation();

