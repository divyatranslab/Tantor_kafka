package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.ExternalClusterNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ExternalClusterNodeRepository extends JpaRepository<ExternalClusterNode, UUID> {

    List<ExternalClusterNode> findByClusterId(UUID clusterId);

    @Modifying
    @Query(value = """
        INSERT INTO kf_external_cluster_nodes (
            id, cluster_id, host, node_id, is_broker, is_controller,
            cpu_usage_pct, memory_used_mb, memory_total_mb, disk_used_gb, disk_total_gb, last_seen
        ) VALUES (
            gen_random_uuid(), :clusterId, :host, :nodeId, :isBroker, :isController,
            NULL, NULL, NULL, NULL, NULL, NULL
        )
        ON CONFLICT (cluster_id, host) DO UPDATE SET
            node_id = EXCLUDED.node_id,
            is_broker = EXCLUDED.is_broker,
            is_controller = EXCLUDED.is_controller
        """, nativeQuery = true)
    void upsertTopology(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("nodeId") Integer nodeId,
        @Param("isBroker") Boolean isBroker,
        @Param("isController") Boolean isController
    );

    @Modifying
    @Query(value = """
        INSERT INTO kf_external_cluster_nodes (
            id, cluster_id, host, node_id, is_broker, is_controller,
            cpu_usage_pct, memory_used_mb, memory_total_mb, disk_used_gb, disk_total_gb, last_seen
        ) VALUES (
            gen_random_uuid(), :clusterId, :host, NULL, NULL, NULL,
            :cpu, :memUsed, :memTotal, :diskUsed, :diskTotal, :lastSeen
        )
        ON CONFLICT (cluster_id, host) DO UPDATE SET
            cpu_usage_pct = EXCLUDED.cpu_usage_pct,
            memory_used_mb = EXCLUDED.memory_used_mb,
            memory_total_mb = EXCLUDED.memory_total_mb,
            disk_used_gb = EXCLUDED.disk_used_gb,
            disk_total_gb = EXCLUDED.disk_total_gb,
            last_seen = EXCLUDED.last_seen
        """, nativeQuery = true)
    void upsertTelemetry(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("cpu") Double cpu,
        @Param("memUsed") Long memUsed,
        @Param("memTotal") Long memTotal,
        @Param("diskUsed") Long diskUsed,
        @Param("diskTotal") Long diskTotal,
        @Param("lastSeen") OffsetDateTime lastSeen
    );
}
