package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 80)
    private String resourceType;

    @Column(name = "entity_id", length = 255)
    private String resourceId;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(nullable = false)
    private String actor;

    @Column(name = "event_category", nullable = false, length = 80)
    private String category;

    @Column(nullable = false, length = 40)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String approval;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false, length = 80)
    private String source;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "previous_hash", length = 64)
    private String previousHash;

    @Column(name = "record_hash", nullable = false, length = 64, updatable = false)
    private String recordHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
