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

    @Column(nullable = false)
    private String name;

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
