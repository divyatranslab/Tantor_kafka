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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    private ExternalClusterService service(ExternalClusterRepository externalClusterRepository) {
        return service(
                externalClusterRepository,
                mock(DiscoveryAgentRepository.class),
                mock(KafkaAdminService.class));
    }

    private ExternalClusterService service(
            ExternalClusterRepository externalClusterRepository,
            DiscoveryAgentRepository discoveryAgentRepository,
            KafkaAdminService kafkaAdminService) {
        return new ExternalClusterService(
                mock(ClusterRepository.class),
                externalClusterRepository,
                mock(ExternalClusterNodeRepository.class),
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