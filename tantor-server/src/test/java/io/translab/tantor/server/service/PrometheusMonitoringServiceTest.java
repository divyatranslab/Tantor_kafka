package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.ExternalClusterNode;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        first.setMemoryUsedMb(200L);
        first.setMemoryTotalMb(1000L);
        ExternalClusterNode second = new ExternalClusterNode();
        second.setClusterId(id);
        second.setNodeId(2);
        second.setMemoryUsedMb(300L);
        second.setMemoryTotalMb(1000L);

        ExternalClusterNodeRepository nodes = mock(ExternalClusterNodeRepository.class);
        when(nodes.findByClusterId(id)).thenReturn(List.of(first, second));
        PrometheusMonitoringService service = new PrometheusMonitoringService(
                mock(ClusterRepository.class), mock(ExternalClusterRepository.class), nodes,
                mock(HostRepository.class), mock(EncryptionService.class), new ObjectMapper());

        assertThat(service.computeHostMemoryPercent(mirror, null)).isEqualTo(25.0);
        assertThat(service.computeHostMemoryPercent(mirror, "2")).isEqualTo(30.0);
    }
}
