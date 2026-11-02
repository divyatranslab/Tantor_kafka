package io.translab.tantor.artifact.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ArtifactAuditRepository extends JpaRepository<ArtifactAuditLog, UUID> {
    Page<ArtifactAuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<ArtifactAuditLog> findByArtifactIdOrderByCreatedAtDesc(String artifactId, Pageable pageable);
}
