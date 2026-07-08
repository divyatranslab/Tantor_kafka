package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "kf_host_audit_log")
@Getter
@Setter
public class HostAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false, length = 255)
    private String hostId;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(length = 255)
    private String event;

    @Column(length = 50)
    private String status;

    @Column(length = 100)
    private String origin;

    @Column(length = 100)
    private String resource;

    @Column(name = "resource_type", length = 50)
    private String resourceType = "HOST";

    @Column(name = "host_name", length = 255)
    private String hostName;

    @Column(name = "host_ip", length = 100)
    private String hostIp;

    @Column(name = "agent_version", length = 100)
    private String agentVersion;

    @Column(name = "java_version", length = 100)
    private String javaVersion;

    @Column(name = "os_name", length = 255)
    private String osName;

    @Column(name = "user_id", length = 255)
    private String userId;

    @Column(name = "actor_user", length = 128)
    private String actorUser;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;
}
