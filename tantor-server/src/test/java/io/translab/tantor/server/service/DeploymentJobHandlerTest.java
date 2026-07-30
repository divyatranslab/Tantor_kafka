package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.domain.JobStepStatus;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeploymentJobHandlerTest {

    @Test
    void preservesRequestingUsernameOnAsynchronousClusterStatusUpdates() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setUpdatedBy("system");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setStatus(JobStatus.PENDING);
        job.setRequestedBy("admin");
        job.setPayload("{\"clusterId\":\"" + clusterId + "\"}");

        JobStep completedStep = new JobStep();
        completedStep.setStatus(JobStepStatus.SUCCESS);

        JobService jobService = mock(JobService.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(completedStep));

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                mock(DeploymentService.class),
                mock(AgentTaskAwaiter.class),
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class)
        );

        handler.execute(job);

        ArgumentCaptor<Cluster> savedClusters = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterRepository, times(2)).save(savedClusters.capture());
        assertThat(savedClusters.getAllValues())
                .allSatisfy(saved -> assertThat(saved.getUpdatedBy()).isEqualTo("admin"));
        assertThat(cluster.getStatus()).isEqualTo("SUCCESS");
    }
}
