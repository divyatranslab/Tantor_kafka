package io.translab.tantor.server.repository;

import io.translab.tantor.server.domain.ExternalClusterNode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExternalClusterNodeRepository extends JpaRepository<ExternalClusterNode, UUID> {

    List<ExternalClusterNode> findByCanonicalClusterUuid(UUID canonicalClusterUuid);

    List<ExternalClusterNode> findByClusterId(UUID clusterId);

    List<ExternalClusterNode> findByClusterIdIn(List<UUID> clusterIds);

    Optional<ExternalClusterNode> findByClusterIdAndNodeId(UUID clusterId, Integer nodeId);

    @Modifying
    void deleteByClusterId(UUID clusterId);

    @Modifying
    @Query(value = """
        INSERT INTO kf_external_cluster_nodes (
            id, cluster_id, host, node_id, is_broker, is_controller, port,
            cpu_usage_pct, memory_used_mb, memory_total_mb, disk_used_gb, disk_total_gb, last_seen
        ) VALUES (
            gen_random_uuid(), :clusterId, :host, :nodeId, :isBroker, :isController, :port,
            NULL, NULL, NULL, NULL, NULL, NULL
        )
        ON CONFLICT (cluster_id, node_id) DO UPDATE SET
            host = EXCLUDED.host,
            is_broker = EXCLUDED.is_broker,
            is_controller = EXCLUDED.is_controller,
            port = EXCLUDED.port
        """, nativeQuery = true)
    void upsertTopology(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("nodeId") Integer nodeId,
        @Param("isBroker") Boolean isBroker,
        @Param("isController") Boolean isController,
        @Param("port") Integer port
    );

    @Modifying
    @Query(value = """
        INSERT INTO kf_external_cluster_nodes AS current_node (
            id, cluster_id, host, node_id, is_broker, is_controller,
            cpu_usage_pct, memory_used_mb, memory_total_mb,
            disk_used_gb, disk_total_gb, disk_used_bytes, disk_total_bytes,
            messages_in_per_sec, bytes_in_per_sec, last_seen
        ) VALUES (
            gen_random_uuid(), :clusterId, :host, :nodeId, NULL, NULL,
            :cpu, :memUsed, :memTotal,
            :diskUsed, :diskTotal, :diskUsedBytes, :diskTotalBytes,
            :messagesInPerSec, :bytesInPerSec, :lastSeen
        )
        ON CONFLICT (cluster_id, node_id) DO UPDATE SET
            cpu_usage_pct = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.cpu_usage_pct, current_node.cpu_usage_pct)
                ELSE current_node.cpu_usage_pct END,
            memory_used_mb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.memory_used_mb, current_node.memory_used_mb)
                ELSE current_node.memory_used_mb END,
            memory_total_mb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.memory_total_mb, current_node.memory_total_mb)
                ELSE current_node.memory_total_mb END,
            disk_used_gb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_used_gb, current_node.disk_used_gb)
                ELSE current_node.disk_used_gb END,
            disk_total_gb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_total_gb, current_node.disk_total_gb)
                ELSE current_node.disk_total_gb END,
            disk_used_bytes = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_used_bytes, current_node.disk_used_bytes)
                ELSE current_node.disk_used_bytes END,
            disk_total_bytes = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_total_bytes, current_node.disk_total_bytes)
                ELSE current_node.disk_total_bytes END,
            messages_in_per_sec = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.messages_in_per_sec, current_node.messages_in_per_sec)
                ELSE current_node.messages_in_per_sec END,
            bytes_in_per_sec = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.bytes_in_per_sec, current_node.bytes_in_per_sec)
                ELSE current_node.bytes_in_per_sec END,
            last_seen = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN EXCLUDED.last_seen
                ELSE current_node.last_seen END
        """, nativeQuery = true)
    void upsertNodeTelemetry(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("nodeId") Integer nodeId,
        @Param("cpu") Double cpu,
        @Param("memUsed") Long memUsed,
        @Param("memTotal") Long memTotal,
        @Param("diskUsed") Long diskUsed,
        @Param("diskTotal") Long diskTotal,
        @Param("diskUsedBytes") Long diskUsedBytes,
        @Param("diskTotalBytes") Long diskTotalBytes,
        @Param("messagesInPerSec") Double messagesInPerSec,
        @Param("bytesInPerSec") Double bytesInPerSec,
        @Param("lastSeen") OffsetDateTime lastSeen
    );

    @Modifying
    @Query(value = """
        INSERT INTO kf_external_cluster_nodes AS current_node (
            id, cluster_id, host, node_id, is_broker, is_controller, port, jmx_exporter_port,
            cpu_usage_pct, memory_used_mb, memory_total_mb,
            disk_used_gb, disk_total_gb, disk_used_bytes, disk_total_bytes, last_seen,
            install_dir, config_file, data_dirs, log_dirs
        ) VALUES (
            gen_random_uuid(), :clusterId, :host, :nodeId, :isBroker, :isController, :port, :jmxPort,
            :cpu, :memUsed, :memTotal,
            :diskUsed, :diskTotal, :diskUsedBytes, :diskTotalBytes, :lastSeen,
            :installDir, :configFile, :dataDirs, :logDirs
        )
        ON CONFLICT (cluster_id, node_id) DO UPDATE SET
            host = EXCLUDED.host,
            is_broker = EXCLUDED.is_broker,
            is_controller = EXCLUDED.is_controller,
            port = COALESCE(EXCLUDED.port, current_node.port),
            jmx_exporter_port = COALESCE(EXCLUDED.jmx_exporter_port, current_node.jmx_exporter_port),
            cpu_usage_pct = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.cpu_usage_pct, current_node.cpu_usage_pct)
                ELSE current_node.cpu_usage_pct END,
            memory_used_mb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.memory_used_mb, current_node.memory_used_mb)
                ELSE current_node.memory_used_mb END,
            memory_total_mb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.memory_total_mb, current_node.memory_total_mb)
                ELSE current_node.memory_total_mb END,
            disk_used_gb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_used_gb, current_node.disk_used_gb)
                ELSE current_node.disk_used_gb END,
            disk_total_gb = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_total_gb, current_node.disk_total_gb)
                ELSE current_node.disk_total_gb END,
            disk_used_bytes = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_used_bytes, current_node.disk_used_bytes)
                ELSE current_node.disk_used_bytes END,
            disk_total_bytes = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN COALESCE(EXCLUDED.disk_total_bytes, current_node.disk_total_bytes)
                ELSE current_node.disk_total_bytes END,
            last_seen = CASE
                WHEN current_node.last_seen IS NULL OR current_node.last_seen <= EXCLUDED.last_seen
                THEN EXCLUDED.last_seen
                ELSE current_node.last_seen END,
            install_dir = COALESCE(EXCLUDED.install_dir, current_node.install_dir),
            config_file = COALESCE(EXCLUDED.config_file, current_node.config_file),
            data_dirs = COALESCE(EXCLUDED.data_dirs, current_node.data_dirs),
            log_dirs = COALESCE(EXCLUDED.log_dirs, current_node.log_dirs)
        """, nativeQuery = true)
    void upsertDiscoveredNode(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("nodeId") Integer nodeId,
        @Param("isBroker") Boolean isBroker,
        @Param("isController") Boolean isController,
        @Param("port") Integer port,
        @Param("jmxPort") Integer jmxPort,
        @Param("cpu") Double cpu,
        @Param("memUsed") Long memUsed,
        @Param("memTotal") Long memTotal,
        @Param("diskUsed") Long diskUsed,
        @Param("diskTotal") Long diskTotal,
        @Param("diskUsedBytes") Long diskUsedBytes,
        @Param("diskTotalBytes") Long diskTotalBytes,
        @Param("lastSeen") OffsetDateTime lastSeen,
        @Param("installDir") String installDir,
        @Param("configFile") String configFile,
        @Param("dataDirs") String dataDirs,
        @Param("logDirs") String logDirs
    );

    @Modifying
    @Query(value = """
        UPDATE kf_external_cluster_nodes SET
            node_id = COALESCE(:nodeId, node_id),
            host = :host,
            is_broker = :isBroker,
            is_controller = :isController,
            port = COALESCE(:port, port),
            jmx_exporter_port = COALESCE(:jmxPort, jmx_exporter_port),
            cpu_usage_pct = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:cpu, cpu_usage_pct)
                ELSE cpu_usage_pct END,
            memory_used_mb = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:memUsed, memory_used_mb)
                ELSE memory_used_mb END,
            memory_total_mb = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:memTotal, memory_total_mb)
                ELSE memory_total_mb END,
            disk_used_gb = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:diskUsed, disk_used_gb)
                ELSE disk_used_gb END,
            disk_total_gb = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:diskTotal, disk_total_gb)
                ELSE disk_total_gb END,
            disk_used_bytes = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:diskUsedBytes, disk_used_bytes)
                ELSE disk_used_bytes END,
            disk_total_bytes = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN COALESCE(:diskTotalBytes, disk_total_bytes)
                ELSE disk_total_bytes END,
            last_seen = CASE
                WHEN last_seen IS NULL OR last_seen <= :lastSeen
                THEN :lastSeen
                ELSE last_seen END,
            install_dir = COALESCE(:installDir, install_dir),
            config_file = COALESCE(:configFile, config_file),
            data_dirs = COALESCE(:dataDirs, data_dirs),
            log_dirs = COALESCE(:logDirs, log_dirs)
        WHERE id = :id
        """, nativeQuery = true)
    int updateDiscoveredNode(
        @Param("id") UUID id,
        @Param("nodeId") Integer nodeId,
        @Param("host") String host,
        @Param("isBroker") Boolean isBroker,
        @Param("isController") Boolean isController,
        @Param("port") Integer port,
        @Param("jmxPort") Integer jmxPort,
        @Param("cpu") Double cpu,
        @Param("memUsed") Long memUsed,
        @Param("memTotal") Long memTotal,
        @Param("diskUsed") Long diskUsed,
        @Param("diskTotal") Long diskTotal,
        @Param("diskUsedBytes") Long diskUsedBytes,
        @Param("diskTotalBytes") Long diskTotalBytes,
        @Param("lastSeen") OffsetDateTime lastSeen,
        @Param("installDir") String installDir,
        @Param("configFile") String configFile,
        @Param("dataDirs") String dataDirs,
        @Param("logDirs") String logDirs
    );

    @Modifying
    @Query(value = """
        UPDATE kf_external_cluster_nodes SET
            cpu_usage_pct = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:cpu, cpu_usage_pct) ELSE cpu_usage_pct END,
            memory_used_mb = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:memUsed, memory_used_mb) ELSE memory_used_mb END,
            memory_total_mb = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:memTotal, memory_total_mb) ELSE memory_total_mb END,
            disk_used_gb = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:diskUsed, disk_used_gb) ELSE disk_used_gb END,
            disk_total_gb = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:diskTotal, disk_total_gb) ELSE disk_total_gb END,
            disk_used_bytes = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:diskUsedBytes, disk_used_bytes) ELSE disk_used_bytes END,
            disk_total_bytes = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN COALESCE(:diskTotalBytes, disk_total_bytes) ELSE disk_total_bytes END,
            last_seen = CASE WHEN last_seen IS NULL OR last_seen <= :lastSeen THEN :lastSeen ELSE last_seen END
        WHERE cluster_id = :clusterId AND host = :host
        """, nativeQuery = true)
    int updateTelemetryByHost(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("cpu") Double cpu,
        @Param("memUsed") Long memUsed,
        @Param("memTotal") Long memTotal,
        @Param("diskUsed") Long diskUsed,
        @Param("diskTotal") Long diskTotal,
        @Param("diskUsedBytes") Long diskUsedBytes,
        @Param("diskTotalBytes") Long diskTotalBytes,
        @Param("lastSeen") OffsetDateTime lastSeen
    );

    @Modifying
    @Query(value = """
        UPDATE kf_external_cluster_nodes SET
            messages_in_per_sec = COALESCE(:messagesInPerSec, messages_in_per_sec),
            bytes_in_per_sec = COALESCE(:bytesInPerSec, bytes_in_per_sec)
        WHERE cluster_id = :clusterId AND host = :host AND is_broker = TRUE
          AND (last_seen IS NULL OR last_seen <= :lastSeen)
        """, nativeQuery = true)
    int updateBrokerIngestionRates(
        @Param("clusterId") UUID clusterId,
        @Param("host") String host,
        @Param("messagesInPerSec") Double messagesInPerSec,
        @Param("bytesInPerSec") Double bytesInPerSec,
        @Param("lastSeen") OffsetDateTime lastSeen
    );
}
