package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.ExternalClusterNode;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrometheusMonitoringServiceTest {

    @Test
    void returnsMirroredExternalClusterOnlyOnce() {
        UUID id = UUID.randomUUID();
        Cluster mirror = new Cluster();
        mirror.setId(id);
        mirror.setName("external-prod");
        mirror.setOriginType("EXTERNAL");
        mirror.setMode("EXTERNAL");
        mirror.setServices(List.of());

        ExternalCluster external = new ExternalCluster();
        external.setId(id);
        external.setName("external-prod");

        ClusterRepository clusters = mock(ClusterRepository.class);
        ExternalClusterRepository externalClusters = mock(ExternalClusterRepository.class);
        ExternalClusterNodeRepository nodes = mock(ExternalClusterNodeRepository.class);
        when(clusters.findByStatusNot("DELETED")).thenReturn(List.of(mirror));
        when(externalClusters.findByStatusNot("DELETED")).thenReturn(List.of(external));
        when(nodes.findByClusterId(id)).thenReturn(List.of());

        PrometheusMonitoringService service = new PrometheusMonitoringService(
                clusters, externalClusters, nodes, mock(HostRepository.class),
                mock(EncryptionService.class), new ObjectMapper());

        assertThat(service.clusters("EXTERNAL"))
                .singleElement()
                .extracting(PrometheusMonitoringService.MonitoringClusterSummary::getId)
                .isEqualTo(id);
    }

    @Test
    void computesExternalHostMemoryFromDiscoveredNodesWithoutInternalAssignments() {
        UUID id = UUID.randomUUID();
        Cluster mirror = new Cluster();
        mirror.setId(id);
        mirror.setOriginType("EXTERNAL");
        mirror.setMode("EXTERNAL");
        mirror.setServices(List.of());

        ExternalClusterNode first = new ExternalClusterNode();
        first.setClusterId(id);
        first.setNodeId(1);
        first.setHost("host-a");
        first.setCpuUsagePct(10.0);
        first.setMemoryUsedMb(200L);
        first.setMemoryTotalMb(1000L);
        ExternalClusterNode second = new ExternalClusterNode();
        second.setClusterId(id);
        second.setNodeId(2);
        second.setHost("host-b");
        second.setCpuUsagePct(30.0);
        second.setMemoryUsedMb(300L);
        second.setMemoryTotalMb(1000L);

        ExternalClusterNodeRepository nodes = mock(ExternalClusterNodeRepository.class);
        when(nodes.findByClusterId(id)).thenReturn(List.of(first, second));
        PrometheusMonitoringService service = new PrometheusMonitoringService(
                mock(ClusterRepository.class), mock(ExternalClusterRepository.class), nodes,
                mock(HostRepository.class), mock(EncryptionService.class), new ObjectMapper());

        assertThat(service.computeHostMemoryPercent(mirror, null)).isEqualTo(25.0);
        assertThat(service.computeHostMemoryPercent(mirror, "2")).isEqualTo(30.0);
        assertThat(service.computeHostMemoryAvailableMb(mirror, null)).isEqualTo(1500L);
        assertThat(service.computeHostMemoryAvailableMb(mirror, "2")).isEqualTo(700L);
        assertThat(service.computeHostMemoryTotalMb(mirror, null)).isEqualTo(2000L);
        assertThat(service.computeHostMemoryTotalMb(mirror, "2")).isEqualTo(1000L);
        assertThat(service.computeExternalSystemCpuPercent(mirror, null)).isEqualTo(20.0);
        assertThat(service.computeExternalSystemCpuPercent(mirror, "2")).isEqualTo(30.0);
    }

    @Test
    void computesInternalAvailableMemoryFromAgentHeartbeatValues() {
        Cluster cluster = new Cluster();
        cluster.setOriginType("INTERNAL");
        cluster.setServices(List.of(
                assignment(cluster, "host-1", 1),
                assignment(cluster, "host-2", 2)
        ));

        Host first = new Host();
        first.setId("host-1");
        first.setMemUsedMb(2048L);
        first.setMemTotalMb(8192L);
        Host second = new Host();
        second.setId("host-2");
        second.setMemUsedMb(4096L);
        second.setMemTotalMb(8192L);

        HostRepository hosts = mock(HostRepository.class);
        when(hosts.findById("host-1")).thenReturn(Optional.of(first));
        when(hosts.findById("host-2")).thenReturn(Optional.of(second));
        PrometheusMonitoringService service = new PrometheusMonitoringService(
                mock(ClusterRepository.class), mock(ExternalClusterRepository.class),
                mock(ExternalClusterNodeRepository.class), hosts,
                mock(EncryptionService.class), new ObjectMapper());

        assertThat(service.computeHostMemoryPercent(cluster, null)).isEqualTo(37.5);
        assertThat(service.computeHostMemoryAvailableMb(cluster, null)).isEqualTo(10240L);
        assertThat(service.computeHostMemoryAvailableMb(cluster, "2")).isEqualTo(4096L);
        assertThat(service.computeHostMemoryTotalMb(cluster, null)).isEqualTo(16384L);
        assertThat(service.computeHostMemoryTotalMb(cluster, "2")).isEqualTo(8192L);
    }

    private ClusterServiceAssignment assignment(Cluster cluster, String hostId, int nodeId) {
        ClusterServiceAssignment assignment = new ClusterServiceAssignment();
        assignment.setCluster(cluster);
        assignment.setHostId(hostId);
        assignment.setNodeId(nodeId);
        return assignment;
    }
}
