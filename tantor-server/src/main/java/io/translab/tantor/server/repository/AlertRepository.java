package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AlertRepository extends JpaRepository<Alert, UUID> {
    List<Alert> findByStatusOrderByCreatedAtDesc(String status);
    long countByStatus(String status);
}
