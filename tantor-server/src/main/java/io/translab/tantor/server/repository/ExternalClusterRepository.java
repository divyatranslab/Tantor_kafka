package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.ExternalCluster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ExternalClusterRepository extends JpaRepository<ExternalCluster, UUID> {
    Optional<ExternalCluster> findByName(String name);
    Optional<ExternalCluster> findByKafkaClusterId(String kafkaClusterId);
    Optional<ExternalCluster> findByBootstrapServers(String bootstrapServers);
    List<ExternalCluster> findByStatusNot(String status);
    Optional<ExternalCluster> findByNameAndStatusNot(String name, String status);
    Optional<ExternalCluster> findByBootstrapServersAndStatusNot(String bootstrapServers, String status);
}
