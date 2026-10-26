package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.ClusterServiceAssignment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClusterServiceAssignmentRepository extends JpaRepository<ClusterServiceAssignment, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select service from ClusterServiceAssignment service where service.id = :id")
    Optional<ClusterServiceAssignment> findByIdForUpdate(@Param("id") UUID id);

    @Query("select service from ClusterServiceAssignment service where service.cluster.id = :clusterId")
    List<ClusterServiceAssignment> findByClusterId(@Param("clusterId") UUID clusterId);

    @Query("select service from ClusterServiceAssignment service where service.cluster.id in :clusterIds")
    List<ClusterServiceAssignment> findByClusterIdIn(@Param("clusterIds") List<UUID> clusterIds);

    @Query("select service from ClusterServiceAssignment service where service.cluster.id = :clusterId and service.hostId = :hostId")
    Optional<ClusterServiceAssignment> findByClusterIdAndHostId(@Param("clusterId") UUID clusterId, @Param("hostId") String hostId);

    @Query("select service from ClusterServiceAssignment service where service.cluster.id = :clusterId and service.role = :role")
    Optional<ClusterServiceAssignment> findByClusterIdAndRole(@Param("clusterId") UUID clusterId, @Param("role") String role);
}
