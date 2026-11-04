package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.repository.ClusterRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RollingRestartJobHandlerTest {

    @Test
    void durableJobPathUsesComprehensiveHealthGateAndStopsWhenHealthIsUnknown() {
        JobService jobs = mock(JobService.class);
        DeploymentService deployment = mock(DeploymentService.class);
        AgentTaskAwaiter awaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusters = mock(ClusterRepository.class);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        ExternalClusterService external = mock(ExternalClusterService.class);
        RollingRestartHealthService health = mock(RollingRestartHealthService.class);
        UUID clusterId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        Cluster cluster = cluster(clusterId);
        when(clusters.findWithServicesById(clusterId)).thenReturn(Optional.of(cluster));
        when(kafka.getControllerId(clusterId)).thenReturn(1);
        when(jobs.getSteps(jobId)).thenReturn(List.of());
        when(health.inspect(eq(clusterId), eq(2), anySet(), anyLong())).thenReturn(
                new RollingRestartHealthService.HealthSnapshot(false, 2, 1, 0, 0, 0, 0,
                        Map.of("kafka", "health inspection timed out")));

        RollingRestartJobHandler handler = new RollingRestartJobHandler(jobs, deployment, awaiter, clusters,
                kafka, health, external, new ObjectMapper());
        ReflectionTestUtils.setField(handler, "healthTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(handler, "healthPollSeconds", 1L);
        AtomicLong clock = new AtomicLong();
        ReflectionTestUtils.setField(handler, "nanoClock", (java.util.function.LongSupplier) clock::get);
        ReflectionTestUtils.setField(handler, "healthSleeper", (RollingRestartJobHandler.HealthSleeper) millis ->
                clock.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis)));
        Job job = new Job();
        job.setId(jobId);
        job.setType(JobType.ROLLING_RESTART);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"confirmSingleNode\":false}");

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Timed out waiting for cluster health");
        verify(health, atLeastOnce()).inspect(eq(clusterId), eq(2), anySet(), anyLong());
        verifyNoInteractions(deployment, awaiter);
        verify(kafka, never()).listTopics(clusterId);
    }

    private Cluster cluster(UUID clusterId) {
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setMode("kraft");
        cluster.setServices(new ArrayList<>(List.of(
                assignment(cluster, "controller-1", "controller", 1),
                assignment(cluster, "controller-2", "controller", 2),
                assignment(cluster, "controller-3", "controller", 3),
                assignment(cluster, "broker-1", "broker", 4),
                assignment(cluster, "broker-2", "broker", 5))));
        return cluster;
    }

    private ClusterServiceAssignment assignment(Cluster cluster, String hostId, String role, int nodeId) {
        ClusterServiceAssignment service = new ClusterServiceAssignment();
        service.setCluster(cluster);
        service.setHostId(hostId);
        service.setRole(role);
        service.setNodeId(nodeId);
        return service;
    }
}
