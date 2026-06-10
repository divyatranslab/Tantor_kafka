package io.translab.tantor.server.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.Data;
import java.util.UUID;

@Entity
@Table(name = "cluster_services")
@Data
public class ClusterServiceAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cluster_id", nullable = false)
    @JsonIgnore
    private Cluster cluster;

    @Column(name = "host_id", nullable = false)
    private String hostId;

    @Column(nullable = false)
    private String role;

    @Column(name = "node_id")
    private Integer nodeId;
}
