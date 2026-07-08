package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "kf_external_clusters")
@Data
public class ExternalCluster {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cluster_name", nullable = false)
    private String name;

    @Column(name = "bootstrap_servers", nullable = false)
    private String bootstrapServers;

    @Column(name = "kafka_version")
    private String kafkaVersion;

    @Column(name = "kafka_mode")
    private String kafkaMode;

    @Column(name = "kafka_cluster_id")
    private String kafkaClusterId;

    @Column(name = "security")
    private String security;

    @Column(name = "broker_count")
    private Integer brokerCount;

    @Column(name = "environment")
    private String environment;

    @Column(name = "install_path")
    private String installPath;

    @Column(name = "log_dirs")
    private String logDirs;

    @Column(name = "listeners")
    private String listeners;

    @Column(name = "advertised_listeners")
    private String advertisedListeners;

    @Column(name = "process_roles")
    private String processRoles;


    @Column(name = "cpu_usage_pct")
    private Double cpuUsagePct;

    @Column(name = "memory_used_mb")
    private Long memoryUsedMb;

    @Column(name = "memory_total_mb")
    private Long memoryTotalMb;

    @Column(name = "disk_used_gb")
    private Long diskUsedGb;

    @Column(name = "disk_total_gb")
    private Long diskTotalGb;

    @Column(name = "is_running")
    private Boolean isRunning;

    // e.g. "SUCCESS", "DEGRADED", "FAILED"
    @Column(name = "status")
    private String status = "PENDING";

    @Column(name = "created_by", nullable = false)
    private String createdBy = "system";

    @Column(name = "updated_by", nullable = false)
    private String updatedBy = "system";

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
