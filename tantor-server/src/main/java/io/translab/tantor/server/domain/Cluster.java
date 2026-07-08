package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "kf_clusters")
@Data
public class Cluster {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "cluster_name", nullable = false)
    private String name;

    @Column(name = "origin_type", nullable = false)
    private String originType = "INTERNAL";

    @Column(name = "kafka_cluster_id")
    private String kafkaClusterId;

    @Column(name = "install_directory")
    private String installDirectory;

    @Column(name = "config_directory")
    private String configDirectory;

    @Column(name = "data_directory")
    private String dataDirectory;

    @Column(name = "log_directory")
    private String logDirectory;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "node_ids", columnDefinition = "jsonb", nullable = false)
    private List<Integer> nodeIds = new ArrayList<>();

    @Column(name = "created_by", nullable = false)
    private String createdBy = "system";

    @Column(name = "updated_by", nullable = false)
    private String updatedBy = "system";

    @Column(name = "kafka_version", nullable = false)
    private String kafkaVersion;

    private String mode;
    private String environment;

    @Column(name = "bootstrap_servers")
    private String bootstrapServers;

    @Column(name = "external_broker_hosts_json", columnDefinition = "TEXT")
    private String externalBrokerHostsJson;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (createdBy == null || createdBy.isBlank()) createdBy = "system";
        if (updatedBy == null || updatedBy.isBlank()) updatedBy = "system";
        if (nodeIds == null) nodeIds = new ArrayList<>();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
        if (updatedBy == null || updatedBy.isBlank()) updatedBy = "system";
    }

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private String status = "PENDING"; // PENDING, RUNNING, VALIDATING, SUCCESS, FAILED, DELETING, DELETED

    @OneToMany(mappedBy = "cluster", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClusterServiceAssignment> services;
}
