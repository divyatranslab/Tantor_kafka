-- =====================================================================
-- Tantor Artifact Repository - Phase 1 schema
-- PostgreSQL 16
--
-- ER overview (ASCII):
--
--   +-------------------+          +----------------------------+
--   |     artifact      |          |   artifact_download_log    |
--   +-------------------+          +----------------------------+
--   | id (PK, UUID)     |<---------| artifact_id (FK)           |
--   | service_type      |   1    * | downloaded_by              |
--   | name              |          | downloaded_at              |
--   | version           |          | remote_addr                |
--   | classifier        |          | verified_checksum (bool)   |
--   | file_name         |          +----------------------------+
--   | relative_path     |
--   | file_size_bytes   |
--   | content_type      |
--   | checksum_sha256    |
--   | checksum_md5      |
--   | status            |
--   | manifest (JSONB)  |
--   | description       |
--   | created_by        |
--   | created_at        |
--   | updated_at        |
--   | version_lock      |
--   +-------------------+
--
-- The download log is included now because BFSI audit requirements demand a
-- record of every artifact pulled by an agent in an air-gapped estate.
-- =====================================================================

CREATE TABLE artifact (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    service_type      VARCHAR(40)  NOT NULL,
    name              VARCHAR(255) NOT NULL,
    version           VARCHAR(80)  NOT NULL,
    classifier        VARCHAR(80),
    file_name         VARCHAR(512) NOT NULL,
    relative_path     VARCHAR(1024) NOT NULL,
    file_size_bytes   BIGINT       NOT NULL,
    content_type      VARCHAR(128) NOT NULL DEFAULT 'application/gzip',
    checksum_sha256   CHAR(64)     NOT NULL,
    checksum_md5      CHAR(32),
    status            VARCHAR(32)  NOT NULL DEFAULT 'UPLOADING',
    manifest          JSONB,
    description       TEXT,
    created_by        VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version_lock      BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_artifact PRIMARY KEY (id),
    CONSTRAINT ck_artifact_service_type CHECK (
        service_type IN ('KAFKA','KAFKA_CONNECT','SCHEMA_REGISTRY','KSQLDB',
                         'CRUISE_CONTROL','PROMETHEUS','GRAFANA')
    ),
    CONSTRAINT ck_artifact_status CHECK (
        status IN ('UPLOADING','AVAILABLE','CORRUPTED','QUARANTINED','DELETED')
    )
);

-- One artifact per (service, version, classifier). COALESCE makes a NULL
-- classifier behave as a single distinct value rather than always-unique NULLs.
CREATE UNIQUE INDEX ux_artifact_identity
    ON artifact (service_type, version, COALESCE(classifier, ''));

CREATE INDEX ix_artifact_service_type ON artifact (service_type);
CREATE INDEX ix_artifact_status       ON artifact (status);
CREATE INDEX ix_artifact_created_at   ON artifact (created_at DESC);
CREATE INDEX ix_artifact_sha256       ON artifact (checksum_sha256);

CREATE TABLE artifact_download_log (
    id                BIGINT GENERATED ALWAYS AS IDENTITY,
    artifact_id       UUID         NOT NULL,
    downloaded_by     VARCHAR(128) NOT NULL DEFAULT 'agent',
    downloaded_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    remote_addr       VARCHAR(64),
    verified_checksum BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_artifact_download_log PRIMARY KEY (id),
    CONSTRAINT fk_download_artifact FOREIGN KEY (artifact_id)
        REFERENCES artifact (id) ON DELETE CASCADE
);

CREATE INDEX ix_download_artifact ON artifact_download_log (artifact_id);
CREATE INDEX ix_download_at       ON artifact_download_log (downloaded_at DESC);
