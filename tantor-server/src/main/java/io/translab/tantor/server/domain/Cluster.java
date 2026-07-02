package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "clusters")
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

    @Column(name = "deleted_at")
    private Instant deletedAt;

    private String status = "PENDING"; // PENDING, RUNNING, VALIDATING, SUCCESS, FAILED, DELETING, DELETED

    @OneToMany(mappedBy = "cluster", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ClusterServiceAssignment> services;
}
