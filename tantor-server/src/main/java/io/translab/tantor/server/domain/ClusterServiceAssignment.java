package io.translab.tantor.server.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.GenericGenerator;

@Entity
@Table(name = "cluster_services")
@Data
public class ClusterServiceAssignment {

    @Id
    @GeneratedValue(generator = "uuid2")
    @GenericGenerator(name = "uuid2", strategy = "uuid2")
    @Column(length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cluster_id", nullable = false)
    private Cluster cluster;

    @Column(name = "host_id", nullable = false)
    private String hostId;

    @Column(nullable = false)
    private String role;

    @Column(name = "node_id")
    private Integer nodeId;
}
