package io.translab.tantor.artifact.repository;

import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ArtifactJpaRepository extends JpaRepository<Artifact, UUID> {

    Optional<Artifact> findByServiceTypeAndVersionAndClassifier(
            ServiceType serviceType, String version, String classifier);

    boolean existsByServiceTypeAndVersionAndClassifier(
            ServiceType serviceType, String version, String classifier);

    List<Artifact> findByStatus(ArtifactStatus status);

    /**
     * Flexible listing with optional service-type and status filters. A null
     * filter matches all values for that dimension.
     */
    @Query("""
            select a from Artifact a
            where (:serviceType is null or a.serviceType = :serviceType)
              and (:status      is null or a.status      = :status)
            """)
    Page<Artifact> search(@Param("serviceType") ServiceType serviceType,
                          @Param("status") ArtifactStatus status,
                          Pageable pageable);
}
