package io.translab.tantor.artifact.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "artifact_audit_log")
public class ArtifactAuditLog {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(nullable = false) private String actor;
    @Column(name = "event_category", nullable = false) private String category;
    @Column(nullable = false) private String action;
    @Column(name = "resource_type", nullable = false) private String resourceType;
    @Column(name = "resource_id") private String resourceId;
    @Column(nullable = false) private String status;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String details;
    @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt = OffsetDateTime.now();

    public UUID getId() { return id; }
    public String getActor() { return actor; } public void setActor(String v) { actor = v; }
    public String getCategory() { return category; } public void setCategory(String v) { category = v; }
    public String getAction() { return action; } public void setAction(String v) { action = v; }
    public String getResourceType() { return resourceType; } public void setResourceType(String v) { resourceType = v; }
    public String getResourceId() { return resourceId; } public void setResourceId(String v) { resourceId = v; }
    public String getStatus() { return status; } public void setStatus(String v) { status = v; }
    public String getDetails() { return details; } public void setDetails(String v) { details = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(OffsetDateTime v) { createdAt = v; }
}
