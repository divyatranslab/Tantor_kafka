package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.HostParcel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HostParcelRepository extends JpaRepository<HostParcel, UUID> {
    List<HostParcel> findByArtifactId(UUID artifactId);
    Optional<HostParcel> findByHostIdAndArtifactId(String hostId, UUID artifactId);
    Optional<HostParcel> findByLastTaskId(UUID lastTaskId);
    List<HostParcel> findByHostIdAndServiceTypeAndActiveTrue(String hostId, String serviceType);
}
