package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.MonitoringConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface MonitoringConfigRepository extends JpaRepository<MonitoringConfig, UUID> {
    Optional<MonitoringConfig> findByClusterId(UUID clusterId);
}
