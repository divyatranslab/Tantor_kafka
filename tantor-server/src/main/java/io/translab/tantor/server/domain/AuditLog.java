package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kf_audit_logs")
@Getter
@Setter
public class AuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 80)
    private String resourceType;

    @Column(name = "resource_id", length = 255)
    private String resourceId;

    @Column(length = 255)
    private String event;

    @Column(length = 255)
    private String resource;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "event_category", nullable = false, length = 80)
    private String category;

    @Column(nullable = false, length = 40)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String approval;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "origin", nullable = false, length = 80)
    private String origin;

    @Column(name = "request_id", length = 100)
    private String requestId;

    @Column(name = "created_time", nullable = false, updatable = false)
    private Instant createdTime = Instant.now();

    @Column(name = "host_name", length = 255)
    private String hostName;

    @Column(name = "host_ip", length = 100)
    private String hostIp;

    @Column(name = "host_id", length = 255)
    private String hostId;

    @Column(name = "artifact_id")
    private UUID artifactId;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "created_by", length = 128)
    private String createdBy;
}
