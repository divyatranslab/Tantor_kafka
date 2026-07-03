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
    Optional<HostParcel> findFirstByHostIdAndArtifactIdOrderByCreatedAtDescIdDesc(String hostId, UUID artifactId);
    Optional<HostParcel> findFirstByLastTaskIdOrderByCreatedAtDescIdDesc(UUID lastTaskId);

    @org.springframework.data.jpa.repository.Query(value = """
        select hp.* from host_parcels hp
        join (
          select distinct on (host_id, artifact_id) id
          from host_parcels
          order by host_id, artifact_id, created_at desc, id desc
        ) latest on latest.id = hp.id
        order by hp.created_at desc
        """, nativeQuery = true)
    List<HostParcel> findLatestStates();

    @org.springframework.data.jpa.repository.Query(value = """
        select hp.* from host_parcels hp
        join (
          select distinct on (host_id, artifact_id) id
          from host_parcels
          where host_id = :hostId and service_type = :serviceType
          order by host_id, artifact_id, created_at desc, id desc
        ) latest on latest.id = hp.id
        where hp.active = true
        """, nativeQuery = true)
    List<HostParcel> findLatestActive(@org.springframework.data.repository.query.Param("hostId") String hostId,
                                      @org.springframework.data.repository.query.Param("serviceType") String serviceType);
}
