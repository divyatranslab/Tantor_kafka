package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Cluster;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClusterRepository extends JpaRepository<Cluster, String> {
}
