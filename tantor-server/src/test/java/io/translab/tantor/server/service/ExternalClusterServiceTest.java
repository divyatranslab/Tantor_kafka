package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.DiscoveryAgent;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.ExternalClusterNode;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.security.TruststoreStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
        when(kafkaAdminService.inspectBootstrapServers(existing, true))
                .thenReturn(java.util.Map.of("connected", true, "clusterId", "recovered-kafka-id"));
        when(discoveryAgentRepository.findByClusterId(existing.getId())).thenReturn(java.util.List.of());

        service.checkExternalClustersHealth();

        assertThat(existing.getKafkaClusterId()).isEqualTo("recovered-kafka-id");
        verify(externalClusterRepository).save(existing);
    }

    @Test
    void scheduledHealthCheckUsesSavedSecurityForEveryBootstrapAndProtocol() {
        ExternalClusterRepository externalClusterRepository = mock(ExternalClusterRepository.class);
        DiscoveryAgentRepository discoveryAgentRepository = mock(DiscoveryAgentRepository.class);
        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        ExternalClusterService service = service(
                externalClusterRepository, discoveryAgentRepository, kafkaAdminService);

        List<ExternalCluster> clusters = new java.util.ArrayList<>();
        for (String host : List.of("192.168.3.213", "192.168.3.229", "192.168.3.228")) {
            for (String protocol : List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")) {
                ExternalCluster cluster = new ExternalCluster();
                cluster.setId(UUID.randomUUID());
                cluster.setName(host + "-" + protocol);
                cluster.setBootstrapServers(host + ":9092");
                cluster.setKafkaClusterId("cluster-id");
                cluster.setSecurityProtocol(protocol);
                cluster.setSaslMechanism(protocol.startsWith("SASL_") ? "SCRAM-SHA-512" : null);
                cluster.setSaslUsername(protocol.startsWith("SASL_") ? "admin" : null);
                cluster.setSaslPasswordEncrypted(protocol.startsWith("SASL_") ? "encrypted-password" : null);
                cluster.setTruststorePath(protocol.endsWith("SSL") ? "/security/" + host + ".p12" : null);
                cluster.setTruststoreType(protocol.endsWith("SSL") ? "PKCS12" : null);
                cluster.setStatus("DEGRADED");
                clusters.add(cluster);
            }
        }

        when(externalClusterRepository.findByStatusNot("DELETED")).thenReturn(clusters);
        when(kafkaAdminService.inspectBootstrapServers(any(ExternalCluster.class),
                org.mockito.ArgumentMatchers.eq(true)))
                .thenReturn(Map.of("connected", true, "clusterId", "cluster-id"));
        for (ExternalCluster cluster : clusters) {
            when(discoveryAgentRepository.findByClusterId(cluster.getId())).thenReturn(List.of());
        }

        service.checkExternalClustersHealth();

        for (ExternalCluster cluster : clusters) {
            verify(kafkaAdminService).inspectBootstrapServers(cluster, true);
        }
        verify(kafkaAdminService, never()).inspectBootstrapServers(any(String.class));
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

    @Test
    void nodeIdPreventsSameHostControllerReportFromOverwritingBrokerPaths() {
        ExternalClusterService service = service(mock(ExternalClusterRepository.class));
        DiscoveryAgent agent = new DiscoveryAgent();
        agent.setHostname("192.168.3.228");
        agent.setIpAddresses("[\"192.168.3.228\"]");

        ExternalClusterService.ExternalDiscoveryReport controllerReport =
                new ExternalClusterService.ExternalDiscoveryReport();
        controllerReport.setNodeId(102);
        controllerReport.setHostname("192.168.3.228");

        ExternalClusterNode broker = new ExternalClusterNode();
        broker.setNodeId(2);
        broker.setHost("192.168.3.228");
        ExternalClusterNode controller = new ExternalClusterNode();
        controller.setNodeId(102);
        controller.setHost("192.168.3.228");

        assertThat(service.matchesDiscoveryNode(broker, controllerReport, agent)).isFalse();
        assertThat(service.matchesDiscoveryNode(controller, controllerReport, agent)).isTrue();
    }

    @Test
    void discoveryReportEnrichesUnknownControllerHostAndPort() {
        ExternalClusterRepository clusterRepository = mock(ExternalClusterRepository.class);
        ExternalClusterNodeRepository nodeRepository = mock(ExternalClusterNodeRepository.class);
        ExternalClusterService service = service(
                mock(ClusterRepository.class), clusterRepository, nodeRepository,
                mock(DiscoveryAgentRepository.class), mock(KafkaAdminService.class));

        ExternalCluster cluster = new ExternalCluster();
        cluster.setId(UUID.randomUUID());
        ExternalClusterNode controller = new ExternalClusterNode();
        controller.setClusterId(cluster.getId());
        controller.setNodeId(102);
        controller.setHost("unknown");
        controller.setPort(0);
        controller.setIsController(true);
        when(nodeRepository.findByClusterId(cluster.getId())).thenReturn(List.of(controller));

        ExternalClusterService.ExternalDiscoveryReport report =
                new ExternalClusterService.ExternalDiscoveryReport();
        report.setNodeId(102);
        report.setHostname("192.168.3.228");
        report.setListeners("CONTROLLER://192.168.3.228:9093");

        ReflectionTestUtils.invokeMethod(service, "applyDiscoveryReportToNodes",
                cluster, report, new DiscoveryAgent());

        assertThat(controller.getHost()).isEqualTo("192.168.3.228");
        assertThat(controller.getPort()).isEqualTo(9093);
        verify(nodeRepository).save(controller);
    }

    @Test
    void metricsUseNodeIdBeforeHostnameOrBootstrapFallback() {
        ExternalClusterRepository clusterRepository = mock(ExternalClusterRepository.class);
        ExternalClusterNodeRepository nodeRepository = mock(ExternalClusterNodeRepository.class);
        DiscoveryAgentRepository agentRepository = mock(DiscoveryAgentRepository.class);
        ExternalClusterService service = service(
                mock(ClusterRepository.class), clusterRepository, nodeRepository,
                agentRepository, mock(KafkaAdminService.class));

        UUID clusterId = UUID.randomUUID();
        ExternalCluster cluster = new ExternalCluster();
        cluster.setId(clusterId);
        cluster.setBootstrapServers("192.168.3.228:9092");
        ExternalClusterNode nodeOne = new ExternalClusterNode();
        nodeOne.setClusterId(clusterId);
        nodeOne.setNodeId(1);
        nodeOne.setHost("192.168.3.229");
        ExternalClusterNode nodeTwo = new ExternalClusterNode();
        nodeTwo.setClusterId(clusterId);
        nodeTwo.setNodeId(2);
        nodeTwo.setHost("192.168.3.228");

        when(clusterRepository.findByBootstrapServersAndStatusNot("192.168.3.228:9092", "DELETED"))
                .thenReturn(Optional.of(cluster));
        when(nodeRepository.findByClusterId(clusterId)).thenReturn(List.of(nodeOne, nodeTwo));
        when(nodeRepository.findByClusterIdAndNodeId(clusterId, 1)).thenReturn(Optional.of(nodeOne));

        ExternalClusterService.ExternalBrokerMetricsDto metrics =
                new ExternalClusterService.ExternalBrokerMetricsDto();
        metrics.setNodeId(1);
        metrics.setHostname("192.168.3.229");
        metrics.setBootstrap("192.168.3.228:9092");
        metrics.setDiskUsedBytes(7_784_919_040L);
        metrics.setDiskTotalBytes(44_286_992_384L);

        service.receiveMetrics("external", metrics);

        assertThat(nodeOne.getDiskUsedBytes()).isEqualTo(7_784_919_040L);
        assertThat(nodeOne.getDiskTotalBytes()).isEqualTo(44_286_992_384L);
        assertThat(nodeTwo.getDiskUsedBytes()).isNull();
        verify(nodeRepository).save(nodeOne);
    }

    @Test
    void staleDiscoveryAgentUsesConfiguredTimeoutAndReturnsOrangeDisconnectedState() {
        ExternalClusterRepository externalClusterRepository = mock(ExternalClusterRepository.class);
        DiscoveryAgentRepository discoveryAgentRepository = mock(DiscoveryAgentRepository.class);
        ExternalClusterService service = service(
                externalClusterRepository, discoveryAgentRepository, mock(KafkaAdminService.class));
        ReflectionTestUtils.setField(service, "discoveryAgentHeartbeatTimeoutSeconds", 45L);

        DiscoveryAgent agent = new DiscoveryAgent();
        agent.setId("agent-1");
        agent.setAgentName("external-agent");
        agent.setStatus("ONLINE");
        agent.setLastHeartbeat(OffsetDateTime.now().minusSeconds(60));
        when(discoveryAgentRepository.findAll()).thenReturn(List.of(agent));

        Map<String, Object> summary = service.listDiscoveryAgents().getFirst();

        assertThat(service.agentStaleSeconds()).isEqualTo(45L);
        assertThat(summary.get("fresh")).isEqualTo(false);
        assertThat(summary.get("health")).isEqualTo("orange");
        assertThat(summary.get("stateLabel")).isEqualTo("Agent disconnected - no recent heartbeat");
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
