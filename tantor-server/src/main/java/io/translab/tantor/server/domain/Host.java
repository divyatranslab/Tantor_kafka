package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "hosts")
@Getter
@Setter
public class Host {
    @Id
    private String id; // agent host_id

    @Column(nullable = false)
    private String hostname;

    @Column(name = "ip_addresses", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String ipAddresses; // Stored as JSON string

    @Column(name = "os_details")
    private String osDetails;

    @Column(name = "agent_version")
    private String agentVersion;

    @Column(nullable = false)
    private String status;

    @Column(name = "last_heartbeat")
    private OffsetDateTime lastHeartbeat;

    @Column(name = "cpu_usage_pct")
    private Double cpuUsagePct;

    @Column(name = "mem_total_mb")
    private Long memTotalMb;

    @Column(name = "mem_used_mb")
    private Long memUsedMb;

    @Column(name = "disk_total_gb")
    private Long diskTotalGb;

    @Column(name = "disk_used_gb")
    private Long diskUsedGb;

    @Column(name = "java_version")
    private String javaVersion;

    @Column(name = "cluster_id")
    private UUID clusterId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
