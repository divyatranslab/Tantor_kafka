package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ExternalCluster;
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
}