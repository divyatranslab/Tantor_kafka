package io.translab.tantor.server.audit;

import io.translab.tantor.server.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID>, JpaSpecificationExecutor<AuditLog> {
    Optional<AuditLog> findFirstByOrderByCreatedAtDescIdDesc();
    List<AuditLog> findAllByOrderByCreatedAtAscIdAsc();
    long countByStatus(String status);
    long countByCategory(String category);

    @Query(value = "select pg_advisory_xact_lock(772904221)", nativeQuery = true)
    void lockLedger();
}
