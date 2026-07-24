package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.security.TruststoreStorageService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalClusterServiceTest {

    @Test
    void preservesPersistedKafkaClusterIdWhenFollowUpDiscoveryReportOmitsIt() {
        ExternalClusterRepository externalClusterRepository = mock(ExternalClusterRepository.class);
        ExternalClusterService service = service(externalClusterRepository);

        ExternalCluster existing = new ExternalCluster();
        existing.setId(UUID.randomUUID());
        existing.setName("external-test");
        existing.setBootstrapServers("192.168.3.208:9092");
        existing.setKafkaClusterId("kafka-assigned-id");

        when(externalClusterRepository.findByBootstrapServersAndStatusNot(
                "192.168.3.208:9092", "DELETED")).thenReturn(Optional.of(existing));
        when(externalClusterRepository.save(existing)).thenReturn(existing);

        ExternalClusterService.ExternalDiscoveryReport report =
                new ExternalClusterService.ExternalDiscoveryReport();
        report.setName("external-test");
        report.setBootstrapServers("192.168.3.208:9092");
        report.setKafkaClusterId(null);
        report.setRunning(true);

        ExternalCluster saved = service.upsertDiscoveryCluster(report);

        assertThat(saved.getKafkaClusterId()).isEqualTo("kafka-assigned-id");
    }

    @Test
    void backfillsMissingKafkaClusterIdDuringHealthCheck() {
        ExternalClusterRepository externalClusterRepository = mock(ExternalClusterRepository.class);
        DiscoveryAgentRepository discoveryAgentRepository = mock(DiscoveryAgentRepository.class);
        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        ExternalClusterService service = service(
                externalClusterRepository, discoveryAgentRepository, kafkaAdminService);

        ExternalCluster existing = new ExternalCluster();
        existing.setId(UUID.randomUUID());
        existing.setName("external-test");
        existing.setBootstrapServers("192.168.3.208:9092");
        existing.setKafkaClusterId(null);
        existing.setStatus("DEGRADED");

        when(externalClusterRepository.findByStatusNot("DELETED")).thenReturn(java.util.List.of(existing));
        when(kafkaAdminService.inspectBootstrapServers("192.168.3.208:9092"))
                .thenReturn(java.util.Map.of("connected", true, "clusterId", "recovered-kafka-id"));
        when(discoveryAgentRepository.findByClusterId(existing.getId())).thenReturn(java.util.List.of());

        service.checkExternalClustersHealth();

        assertThat(existing.getKafkaClusterId()).isEqualTo("recovered-kafka-id");
        verify(externalClusterRepository).save(existing);
    }

    @Test
    void recreatingDeletedExternalClusterCreatesFreshRowAndTimestamp() {
        UUID deletedId = UUID.randomUUID();
        UUID freshId = UUID.randomUUID();
        ExternalClusterRepository externalClusterRepository = mock(ExternalClusterRepository.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        ExternalClusterNodeRepository nodeRepository = mock(ExternalClusterNodeRepository.class);
        DiscoveryAgentRepository discoveryAgentRepository = mock(DiscoveryAgentRepository.class);
        ExternalClusterService service = service(
                clusterRepository,
                externalClusterRepository,
                nodeRepository,
                discoveryAgentRepository,
                mock(KafkaAdminService.class));

        ExternalCluster deleted = new ExternalCluster();
        deleted.setId(deletedId);
        deleted.setName("external-test");
        deleted.setBootstrapServers("192.168.3.208:9092");
        deleted.setKafkaClusterId("kafka-id");
        deleted.setStatus("DELETED");
        deleted.setCreatedAt(Instant.parse("2024-01-01T00:00:00Z"));

        when(externalClusterRepository.findByStatus("DELETED")).thenReturn(java.util.List.of(deleted));
        when(externalClusterRepository.findById(deletedId)).thenReturn(Optional.of(deleted));
        when(discoveryAgentRepository.findByClusterId(deletedId)).thenReturn(java.util.List.of());
        when(externalClusterRepository.findByStatusNot("DELETED")).thenReturn(java.util.List.of());
        when(externalClusterRepository.findByBootstrapServersAndStatusNot("192.168.3.208:9092", "DELETED"))
                .thenReturn(Optional.empty());
        when(externalClusterRepository.findByNameAndStatusNot("external-test", "DELETED"))
                .thenReturn(Optional.empty());
        when(externalClusterRepository.save(any(ExternalCluster.class))).thenAnswer(invocation -> {
            ExternalCluster saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(freshId);
            }
            return saved;
        });

        ExternalClusterService.BootstrapExternalClusterRequest request =
                new ExternalClusterService.BootstrapExternalClusterRequest();
        request.setName("external-test");
        request.setBootstrapServers("192.168.3.208:9092");
        request.setClusterId("kafka-id");
        request.setKafkaVersion("4.1.0");

        ExternalCluster recreated = service.registerBootstrapCluster(request);

        assertThat(recreated).isNotSameAs(deleted);
        assertThat(recreated.getId()).isEqualTo(freshId);
        assertThat(recreated.getCreatedAt()).isAfter(deleted.getCreatedAt());
        verify(clusterRepository).purgeById(deletedId);
        verify(externalClusterRepository).delete(deleted);
    }
    @Test
    void physicallyDeletesExternalClusterAndItsInventoryMirror() {
        UUID clusterId = UUID.randomUUID();
        ExternalClusterRepository externalClusterRepository = mock(ExternalClusterRepository.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        ExternalClusterNodeRepository nodeRepository = mock(ExternalClusterNodeRepository.class);
        DiscoveryAgentRepository discoveryAgentRepository = mock(DiscoveryAgentRepository.class);
        ExternalClusterService service = service(
                clusterRepository,
                externalClusterRepository,
                nodeRepository,
                discoveryAgentRepository,
                mock(KafkaAdminService.class));

        ExternalCluster existing = new ExternalCluster();
        existing.setId(clusterId);
        existing.setName("external-test");
        existing.setBootstrapServers("192.168.3.208:9092");
        existing.setKafkaClusterId("kafka-id");
        when(externalClusterRepository.findById(clusterId)).thenReturn(Optional.of(existing));
        when(discoveryAgentRepository.findByClusterId(clusterId)).thenReturn(java.util.List.of());

        assertThat(service.deleteExternalCluster(clusterId)).contains(existing);

        verify(nodeRepository).deleteByClusterId(clusterId);
        verify(clusterRepository).purgeById(clusterId);
        verify(externalClusterRepository).delete(existing);
        verify(externalClusterRepository).flush();
        verify(externalClusterRepository, never()).save(existing);
    }
    private ExternalClusterService service(ExternalClusterRepository externalClusterRepository) {
        return service(
                mock(ClusterRepository.class),
                externalClusterRepository,
                mock(ExternalClusterNodeRepository.class),
                mock(DiscoveryAgentRepository.class),
                mock(KafkaAdminService.class));
    }

    private ExternalClusterService service(
            ExternalClusterRepository externalClusterRepository,
            DiscoveryAgentRepository discoveryAgentRepository,
            KafkaAdminService kafkaAdminService) {
        return service(
                mock(ClusterRepository.class),
                externalClusterRepository,
                mock(ExternalClusterNodeRepository.class),
                discoveryAgentRepository,
                kafkaAdminService);
    }

    private ExternalClusterService service(
            ClusterRepository clusterRepository,
            ExternalClusterRepository externalClusterRepository,
            ExternalClusterNodeRepository nodeRepository,
            DiscoveryAgentRepository discoveryAgentRepository,
            KafkaAdminService kafkaAdminService) {
        return new ExternalClusterService(
                clusterRepository,
                externalClusterRepository,
                nodeRepository,
                mock(ClusterServiceAssignmentRepository.class),
                mock(HostRepository.class),
                discoveryAgentRepository,
                kafkaAdminService,
                new ObjectMapper(),
                mock(ActivityAlertService.class),
                mock(AuditService.class),
                mock(EncryptionService.class),
                mock(TruststoreStorageService.class),
                mock(PrometheusMonitoringService.class)
        );
    }
}