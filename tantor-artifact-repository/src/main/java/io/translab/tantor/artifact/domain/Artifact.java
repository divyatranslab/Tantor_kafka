package io.translab.tantor.artifact.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistent metadata for a single stored artifact. The binary itself lives on
 * disk at {@link #relativePath}; this row is the index entry.
 */
@Entity
@Table(name = "artifact")
public class Artifact {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 40)
    private ServiceType serviceType;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "version", nullable = false, length = 80)
    private String version;

    @Column(name = "classifier", length = 80)
    private String classifier;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "relative_path", nullable = false, length = 1024)
    private String relativePath;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType = "application/gzip";

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "checksum_md5", length = 32)
    private String checksumMd5;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ArtifactStatus status = ArtifactStatus.UPLOADING;

    /** Serialized {@code ManifestDto}; stored as PostgreSQL JSONB. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "manifest", columnDefinition = "jsonb")
    private String manifest;

    @Column(name = "description")
    private String description;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy = "system";

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    @Column(name = "version_lock", nullable = false)
    private long versionLock;

    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    // --- getters / setters ------------------------------------------------

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public ServiceType getServiceType() { return serviceType; }
    public void setServiceType(ServiceType serviceType) { this.serviceType = serviceType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getClassifier() { return classifier; }
    public void setClassifier(String classifier) { this.classifier = classifier; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getRelativePath() { return relativePath; }
    public void setRelativePath(String relativePath) { this.relativePath = relativePath; }

    public long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }

    public String getChecksumMd5() { return checksumMd5; }
    public void setChecksumMd5(String checksumMd5) { this.checksumMd5 = checksumMd5; }

    public ArtifactStatus getStatus() { return status; }
    public void setStatus(ArtifactStatus status) { this.status = status; }

    public String getManifest() { return manifest; }
    public void setManifest(String manifest) { this.manifest = manifest; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }

    public long getVersionLock() { return versionLock; }
}
