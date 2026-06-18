package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Cluster;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface ClusterRepository extends JpaRepository<Cluster, UUID> {
    java.util.List<Cluster> findByStatusNot(String status);
    java.util.Optional<Cluster> findByNameAndStatusNot(String name, String status);
    java.util.List<Cluster> findByModeAndStatusNot(String mode, String status);
    java.util.Optional<Cluster> findByModeAndNameAndStatusNot(String mode, String name, String status);
    java.util.Optional<Cluster> findByModeAndBootstrapServersAndStatusNot(String mode, String bootstrapServers, String status);
}
