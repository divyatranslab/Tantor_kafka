package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RollingRestartServiceTest {

    @Test
    void pausesWithoutDispatchingRestartWhenPrecheckFails() {
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        ExternalClusterService externalClusterService = mock(ExternalClusterService.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        RollingRestartHealthService healthService = mock(RollingRestartHealthService.class);
        RollingRestartService service = new RollingRestartService(clusterRepository, deploymentService,
                kafkaAdminService, externalClusterService, taskRepository, healthService);

        UUID clusterId = UUID.randomUUID();
        Cluster cluster = cluster(clusterId);
        when(clusterRepository.findWithServicesById(clusterId)).thenReturn(Optional.of(cluster));
        when(healthService.inspect(eq(clusterId), eq(3), anySet())).thenReturn(
                new RollingRestartHealthService.HealthSnapshot(false, 3, 1, 0, 2, 0, 42,
                        Map.of("underReplicatedPartitions", "2 partition(s) are under-replicated")));

        service.executeRollingRestart(clusterId, "restart-1");

        assertThat(service.getTaskStatus("restart-1"))
                .isEqualTo("PAUSED: Pre-restart health gate failed: 2 partition(s) are under-replicated");
        verifyNoInteractions(deploymentService);
    }

    @Test
    void externalRestartUsesExpectedBrokerCountForHealthCheck() throws Exception {

        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        ExternalClusterService externalClusterService = mock(ExternalClusterService.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        RollingRestartHealthService healthService = mock(RollingRestartHealthService.class);

        RollingRestartService service = new RollingRestartService(
                clusterRepository,
                deploymentService,
                kafkaAdminService,
                externalClusterService,
                taskRepository,
                healthService);

        UUID clusterId = UUID.randomUUID();

        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setMode("EXTERNAL");

        when(clusterRepository.findWithServicesById(clusterId))
                .thenReturn(Optional.of(cluster));

        when(externalClusterService.getExpectedBrokerCount(clusterId))
                .thenReturn(3);

        when(externalClusterService.queueRestart(clusterId))
                .thenReturn(Map.of("taskId", "external-task-1"));

        when(externalClusterService.getExternalTaskStatus("external-task-1"))
                .thenReturn(Map.of("status", "SUCCESS"));

        when(healthService.inspect(eq(clusterId), eq(3), anySet(), anyLong()))
                .thenReturn(new RollingRestartHealthService.HealthSnapshot(
                        true, 3, 1, 0, 0, 0, 0, Map.of()));

        // Configure one successful health sample to avoid sleeping.
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "stableSamples", 1);

        service.executeRollingRestart(clusterId, "external-restart-1");

        assertThat(service.getTaskStatus("external-restart-1"))
                .isEqualTo("COMPLETED successfully.");

        verify(healthService).inspect(eq(clusterId), eq(3), anySet(), anyLong());
    }

    @Test
    void externalRestartDoesNotDispatchWhenBrokerInventoryIsInvalid() {

        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        ExternalClusterService externalClusterService = mock(ExternalClusterService.class);
        TaskRepository taskRepository = mock(TaskRepository.class);
        RollingRestartHealthService healthService = mock(RollingRestartHealthService.class);

        RollingRestartService service = new RollingRestartService(
                clusterRepository,
                deploymentService,
                kafkaAdminService,
                externalClusterService,
                taskRepository,
                healthService);

        UUID clusterId = UUID.randomUUID();

        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setMode("EXTERNAL");

        when(clusterRepository.findWithServicesById(clusterId))
                .thenReturn(Optional.of(cluster));

        when(externalClusterService.getExpectedBrokerCount(clusterId))
                .thenThrow(new IllegalStateException("External broker inventory is empty"));

        service.executeRollingRestart(clusterId, "external-restart-2");

        assertThat(service.getTaskStatus("external-restart-2"))
                .contains("PAUSED: External broker inventory is empty");

        verify(externalClusterService, never()).queueRestart(clusterId);
        verifyNoInteractions(healthService);
    }

    private Cluster cluster(UUID clusterId) {
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setMode("kraft");
        List<ClusterServiceAssignment> services = new ArrayList<>();
        services.add(service(cluster, "controller-1", "controller", 1));
        services.add(service(cluster, "controller-2", "controller", 2));
        services.add(service(cluster, "controller-3", "controller", 3));
        services.add(service(cluster, "broker-1", "broker", 4));
        services.add(service(cluster, "broker-2", "broker", 5));
        services.add(service(cluster, "broker-3", "broker", 6));
        cluster.setServices(services);
        return cluster;
    }

    private ClusterServiceAssignment service(Cluster cluster, String hostId, String role, int nodeId) {
        ClusterServiceAssignment assignment = new ClusterServiceAssignment();
        assignment.setCluster(cluster);
        assignment.setHostId(hostId);
        assignment.setRole(role);
        assignment.setNodeId(nodeId);
        return assignment;
    }
}
