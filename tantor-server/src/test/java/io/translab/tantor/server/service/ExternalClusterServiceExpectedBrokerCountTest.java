package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.audit.AuditService;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalClusterServiceExpectedBrokerCountTest {

    @Mock
    private ClusterRepository clusterRepository;

    @Mock
    private ExternalClusterRepository externalClusterRepository;

    @Mock
    private ExternalClusterNodeRepository externalClusterNodeRepository;

    @Mock
    private ClusterServiceAssignmentRepository clusterServiceAssignmentRepository;

    @Mock
    private HostRepository hostRepository;

    @Mock
    private DiscoveryAgentRepository discoveryAgentRepository;

    @Mock
    private KafkaAdminService kafkaAdminService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private ActivityAlertService activityAlertService;

    @Mock
    private AuditService auditService;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private TruststoreStorageService truststoreStorageService;

    @Mock
    private PrometheusMonitoringService prometheusMonitoringService;

    @InjectMocks
    private ExternalClusterService externalClusterService;

    @Test
    void shouldReturnDistinctBrokerCountWhenDuplicateNodeIdsExist() {
        UUID clusterId = UUID.randomUUID();
        ExternalCluster cluster = externalCluster(clusterId);

        when(externalClusterRepository.findById(clusterId))
                .thenReturn(Optional.of(cluster));

        when(externalClusterNodeRepository.findByClusterId(clusterId))
                .thenReturn(List.of(
                        brokerNode("broker-1", 1),
                        brokerNode("broker-1-duplicate", 1),
                        brokerNode("broker-2", 2),
                        brokerNode("broker-3", 3)));

        when(discoveryAgentRepository.findByClusterId(clusterId))
                .thenReturn(List.of());

        int actualCount = externalClusterService.getExpectedBrokerCount(clusterId);

        // Duplicate node ID 1 must not increase the expected broker count.
        assertThat(actualCount).isEqualTo(3);
    }

    @Test
    void shouldThrowExceptionWhenBrokerNodeIdIsMissing() {
        UUID clusterId = UUID.randomUUID();
        ExternalCluster cluster = externalCluster(clusterId);

        when(externalClusterRepository.findById(clusterId))
                .thenReturn(Optional.of(cluster));

        when(externalClusterNodeRepository.findByClusterId(clusterId))
                .thenReturn(List.of(
                        brokerNode("broker-1", 1),
                        brokerNode("broker-2", null)));

        when(discoveryAgentRepository.findByClusterId(clusterId))
                .thenReturn(List.of());

        assertThatThrownBy(
                () -> externalClusterService.getExpectedBrokerCount(clusterId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or invalid node ID");
    }

    @Test
    void shouldThrowExceptionWhenBrokerInventoryIsEmpty() {
        UUID clusterId = UUID.randomUUID();
        ExternalCluster cluster = externalCluster(clusterId);

        when(externalClusterRepository.findById(clusterId))
                .thenReturn(Optional.of(cluster));

        when(externalClusterNodeRepository.findByClusterId(clusterId))
                .thenReturn(List.of());

        when(discoveryAgentRepository.findByClusterId(clusterId))
                .thenReturn(List.of());

        assertThatThrownBy(
                () -> externalClusterService.getExpectedBrokerCount(clusterId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inventory is empty");
    }

    @Test
    void shouldNotCountControllerOnlyNodesAsBrokers() {
        UUID clusterId = UUID.randomUUID();
        ExternalCluster cluster = externalCluster(clusterId);

        ExternalClusterNode controller = new ExternalClusterNode();
        controller.setHost("controller-1");
        controller.setNodeId(10);
        controller.setIsBroker(false);
        controller.setIsController(true);

        when(externalClusterRepository.findById(clusterId))
                .thenReturn(Optional.of(cluster));

        when(externalClusterNodeRepository.findByClusterId(clusterId))
                .thenReturn(List.of(
                        brokerNode("broker-1", 1),
                        brokerNode("broker-2", 2),
                        controller));

        when(discoveryAgentRepository.findByClusterId(clusterId))
                .thenReturn(List.of());

        assertThat(externalClusterService.getExpectedBrokerCount(clusterId))
                .isEqualTo(2);
    }

    private ExternalCluster externalCluster(UUID clusterId) {
        ExternalCluster cluster = new ExternalCluster();
        cluster.setId(clusterId);
        cluster.setKafkaMode("KRaft");
        return cluster;
    }

    private ExternalClusterNode brokerNode(String host, Integer nodeId) {
        ExternalClusterNode node = new ExternalClusterNode();
        node.setHost(host);
        node.setNodeId(nodeId);
        node.setIsBroker(true);
        node.setIsController(false);
        return node;
    }
}