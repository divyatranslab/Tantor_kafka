package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.repository.HostParcelRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.ActivityAlertService;
import io.translab.tantor.server.service.BrokerMetricsCacheService;
import io.translab.tantor.server.service.ClusterOverviewService;
import io.translab.tantor.server.service.DeploymentService;
import io.translab.tantor.server.service.ExternalClusterService;
import io.translab.tantor.server.service.HostStatusService;
import io.translab.tantor.server.service.JobService;
import io.translab.tantor.server.service.KafkaAdminService;
import io.translab.tantor.server.util.RoleAuthenticationUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClusterControllerHostReleaseTest {

    @Mock DeploymentService deploymentService;
    @Mock ClusterRepository clusterRepository;
    @Mock ExternalClusterRepository externalClusterRepository;
    @Mock ExternalClusterNodeRepository externalClusterNodeRepository;
    @Mock TaskRepository taskRepository;
    @Mock HostRepository hostRepository;
    @Mock HostParcelRepository hostParcelRepository;
    @Mock BrokerMetricsCacheService brokerMetricsCacheService;
    @Mock ClusterOverviewService clusterOverviewService;
    @Mock ObjectMapper objectMapper;
    @Mock ActivityAlertService activityAlertService;
    @Mock HostStatusService hostStatusService;
    @Mock ExternalClusterService externalClusterService;
    @Mock KafkaAdminService kafkaAdminService;
    @Mock JobService jobService;
    @Mock AuditService auditService;
    @Mock DiscoveryAgentRepository discoveryAgentRepository;
    @Mock RoleAuthenticationUtil roleAuthenticationUtil;

    @InjectMocks
    ClusterController controller;

    @Test
    void deletingClusterReleasesOccupiedHost() {
        UUID clusterId = UUID.randomUUID();
        String hostId = "agent-vm-229";

        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setMode("INTERNAL");
        cluster.setKafkaVersion("3.9.2");
        cluster.setConfigJson("{}");

        ClusterServiceAssignment assignment = new ClusterServiceAssignment();
        assignment.setCluster(cluster);
        assignment.setHostId(hostId);
        assignment.setRole("broker_controller");
        assignment.setNodeId(1);
        cluster.setServices(List.of(assignment));

        Host host = new Host();
        host.setId(hostId);
        host.setClusterId(clusterId);
        host.setStatus("OCCUPIED");

        when(roleAuthenticationUtil.canAccess(any(), anyString())).thenReturn(true);
        when(externalClusterRepository.findById(clusterId)).thenReturn(Optional.empty());
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(hostRepository.findById(hostId)).thenReturn(Optional.of(host));
        when(deploymentService.deleteClusterFromHost(
                clusterId, hostId, cluster.getKafkaVersion(), cluster.getConfigJson()))
                .thenReturn(UUID.randomUUID());

        controller.deleteCluster("Bearer test-token", clusterId);

        assertThat(host.getClusterId()).isNull();
        assertThat(host.getStatus()).isEqualTo("ONLINE");
        verify(hostRepository).save(host);
        verify(clusterRepository).purgeById(clusterId);
    }
}
