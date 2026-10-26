package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.domain.JobStepStatus;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.translab.tantor.server.domain.Task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        handler.execute(job);

        ArgumentCaptor<Cluster> savedClusters = ArgumentCaptor.forClass(Cluster.class);
        verify(clusterRepository, times(1)).save(savedClusters.capture());
        assertThat(savedClusters.getAllValues())
                .allSatisfy(saved -> assertThat(saved.getUpdatedBy()).isEqualTo("admin"));
        assertThat(cluster.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void successfulDeploymentMarksClusterSuccessOnlyAfterAllRequiredStepsSucceed() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\"}");

        UUID taskId = UUID.randomUUID();
        JobStep step = new JobStep();
        step.setId(UUID.randomUUID());
        step.setStatus(JobStepStatus.IN_PROGRESS);
        step.setAgentTaskId(taskId);
        step.setPayload("{\"operation\":\"connectivity\"}");

        JobService jobService = mock(JobService.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step));

        io.translab.tantor.server.domain.Task task = new io.translab.tantor.server.domain.Task();
        task.setStatus("SUCCESS");
        when(taskAwaiter.await(taskId)).thenReturn(task);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                mock(DeploymentService.class),
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        handler.execute(job);

        assertThat(cluster.getStatus()).isEqualTo("SUCCESS");
        verify(clusterRepository, times(1)).save(cluster);
    }

    @Test
    void failedPreconditionDoesNotMarkClusterRunning() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\"}");

        JobService jobService = mock(JobService.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of());

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                mock(DeploymentService.class),
                mock(AgentTaskAwaiter.class),
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("no persisted steps");

        assertThat(cluster.getStatus()).isEqualTo("PENDING");
        verify(clusterRepository, never()).save(cluster);
    }

    @Test
    void failureInLaterDeploymentStepDoesNotLeaveClusterMarkedRunning() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\"}");

        UUID taskId = UUID.randomUUID();
        JobStep step = new JobStep();
        step.setId(UUID.randomUUID());
        step.setStatus(JobStepStatus.IN_PROGRESS);
        step.setAgentTaskId(taskId);
        step.setPayload("{\"operation\":\"connectivity\"}");

        JobService jobService = mock(JobService.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step));
        when(taskAwaiter.await(taskId)).thenThrow(new RuntimeException("Agent task failed"));

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                mock(DeploymentService.class),
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Agent task failed");

        assertThat(cluster.getStatus()).isEqualTo("FAILED");
        verify(clusterRepository, times(1)).save(cluster);
    }

    @Test
    void allBatchDeployStepsSucceedAndReachExpectedSuccessfulState() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        Task task1 = new Task();
        task1.setId(task1Id);
        task1.setStatus("SUCCESS");
        task1.setLogOutput("Kafka installed on host-1");

        Task task2 = new Task();
        task2.setId(task2Id);
        task2.setStatus("SUCCESS");
        task2.setLogOutput("Kafka installed on host-2");

        when(taskAwaiter.await(task1Id)).thenReturn(task1);
        when(taskAwaiter.await(task2Id)).thenReturn(task2);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        handler.execute(job);

        verify(jobService).completeStep(step1.getId(), "Kafka installed on host-1");
        verify(jobService).completeStep(step2.getId(), "Kafka installed on host-2");
        assertThat(cluster.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void dispatchFailureCancelsEarlierDispatchedStepsAndPreservesAccurateStates() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        JobStep step3 = new JobStep();
        step3.setId(UUID.randomUUID());
        step3.setName("Deploy Node 3");
        step3.setTargetId("host-3");
        step3.setStatus(JobStepStatus.PENDING);
        step3.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-3\",\"node_id\":\"3\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2, step3));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id)
                .thenThrow(new RuntimeException("Agent unreachable for host-2"));

        TaskRepository taskRepository = mock(TaskRepository.class);
        Task task1Record = new Task();
        task1Record.setId(task1Id);
        task1Record.setStatus("PENDING");
        when(taskRepository.findById(task1Id)).thenReturn(Optional.of(task1Record));
        when(taskRepository.cancelPendingTask(eq(task1Id), any())).thenReturn(1);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Agent unreachable for host-2");

        verify(jobService).failStep(step2.getId(), "Agent unreachable for host-2");
        verify(taskRepository).cancelPendingTask(eq(task1Id), contains("Cancelled due to batch deployment failure"));
        verify(taskRepository, never()).save(any());
        ArgumentCaptor<String> step1CancelReason = ArgumentCaptor.forClass(String.class);
        verify(jobService).failStep(eq(step1.getId()), step1CancelReason.capture());
        assertThat(step1CancelReason.getValue()).contains("Dispatched task cancelled before agent execution");
        verify(jobService, never()).failStep(eq(step3.getId()), any());
        verify(jobService, never()).startStep(step3.getId());
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void agentTaskFailureWhileOtherTasksOutstandingAssignsAppropriateStates() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        JobStep step3 = new JobStep();
        step3.setId(UUID.randomUUID());
        step3.setName("Deploy Node 3");
        step3.setTargetId("host-3");
        step3.setStatus(JobStepStatus.PENDING);
        step3.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-3\",\"node_id\":\"3\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID task3Id = UUID.randomUUID();
        UUID cleanup1Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2, step3));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id, task3Id);

        Task task1 = new Task();
        task1.setId(task1Id);
        task1.setStatus("SUCCESS");
        when(taskAwaiter.await(task1Id)).thenReturn(task1);

        when(taskAwaiter.await(task2Id)).thenThrow(new RuntimeException("Agent task 2 crashed"));

        Task cleanupTask1 = new Task();
        cleanupTask1.setId(cleanup1Id);
        cleanupTask1.setStatus("SUCCESS");
        when(deploymentService.deleteClusterFromHost(any(), any(), any(), any())).thenReturn(cleanup1Id);
        when(taskAwaiter.await(cleanup1Id)).thenReturn(cleanupTask1);

        TaskRepository taskRepository = mock(TaskRepository.class);
        Task task3Record = new Task();
        task3Record.setId(task3Id);
        task3Record.setStatus("PENDING");
        when(taskRepository.findById(task3Id)).thenReturn(Optional.of(task3Record));
        when(taskRepository.cancelPendingTask(eq(task3Id), any())).thenReturn(1);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Agent task 2 crashed");

        verify(jobService).completeStep(eq(step1.getId()), any());
        verify(jobService).rolledBackStep(eq(step1.getId()), any());
        verify(jobService).failStep(step2.getId(), "Agent task 2 crashed");

        verify(taskRepository).cancelPendingTask(eq(task3Id), contains("Cancelled due to batch deployment failure"));
        verify(taskRepository, never()).save(any());
        ArgumentCaptor<String> step3CancelReason = ArgumentCaptor.forClass(String.class);
        verify(jobService).failStep(eq(step3.getId()), step3CancelReason.capture());
        assertThat(step3CancelReason.getValue()).contains("Dispatched task cancelled before agent execution");

        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void successfulStepCompensatedWhenSubsequentStepFails() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\",\"serviceConfigJson\":\"{\\\"a\\\":1}\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID cleanupTaskId = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        Task task1 = new Task();
        task1.setId(task1Id);
        task1.setStatus("SUCCESS");
        when(taskAwaiter.await(task1Id)).thenReturn(task1);

        when(taskAwaiter.await(task2Id)).thenThrow(new RuntimeException("Disk full on host-2"));

        when(deploymentService.deleteClusterFromHost(clusterId, "host-1", "3.5.1", "{\"a\":1}"))
                .thenReturn(cleanupTaskId);
        Task cleanupTask = new Task();
        cleanupTask.setId(cleanupTaskId);
        cleanupTask.setStatus("SUCCESS");
        cleanupTask.setLogOutput("Deleted cluster from host-1");
        when(taskAwaiter.await(cleanupTaskId)).thenReturn(cleanupTask);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Disk full on host-2");

        verify(deploymentService).deleteClusterFromHost(clusterId, "host-1", "3.5.1", "{\"a\":1}");
        verify(jobService).rolledBackStep(step1.getId(), "Deleted cluster from host-1");
        verify(jobService).failStep(step2.getId(), "Disk full on host-2");
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void retryingPartiallyFailedBatchSkipsAlreadyCompletedSteps() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.SUCCESS);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task2Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task2Id);

        Task task2 = new Task();
        task2.setId(task2Id);
        task2.setStatus("SUCCESS");
        when(taskAwaiter.await(task2Id)).thenReturn(task2);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        handler.execute(job);

        verify(deploymentService, never()).deployKafkaToHost(
                eq(clusterId),
                eq("host-1"),
                any(), any(), any(), any(), any(), any(), any()
        );
        verify(deploymentService, never()).deleteClusterFromHost(
                eq(clusterId),
                eq("host-1"),
                any(), any()
        );

        verify(deploymentService).deployKafkaToHost(
                eq(clusterId),
                eq("host-2"),
                any(), any(), any(), any(), any(), any(), any()
        );
        verify(jobService).completeStep(eq(step2.getId()), any());
        assertThat(cluster.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void rollbackReusesCompensationMechanismForCompletedDeploySteps() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("RUNNING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step = new JobStep();
        step.setId(UUID.randomUUID());
        step.setName("Deploy Node 1");
        step.setTargetId("host-1");
        step.setStatus(JobStepStatus.SUCCESS);
        step.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"serviceConfigJson\":\"{}\"}");

        UUID cleanupTaskId = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findWithServicesById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step));

        when(deploymentService.deleteClusterFromHost(clusterId, "host-1", "3.5.1", "{}"))
                .thenReturn(cleanupTaskId);
        Task cleanupTask = new Task();
        cleanupTask.setId(cleanupTaskId);
        cleanupTask.setStatus("SUCCESS");
        when(taskAwaiter.await(cleanupTaskId)).thenReturn(cleanupTask);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        handler.rollback(job);

        verify(deploymentService).deleteClusterFromHost(clusterId, "host-1", "3.5.1", "{}");
        verify(jobService).rolledBackStep(eq(step.getId()), any());
        assertThat(cluster.getStatus()).isEqualTo("ROLLED_BACK");
    }

    @Test
    void taskSucceedingRemotelyDuringFailureHandlingIsIncludedInCompensation() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID cleanupTaskId2 = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        when(taskAwaiter.await(task1Id)).thenThrow(new RuntimeException("Host-1 network timeout"));

        Task task2Record = new Task();
        task2Record.setId(task2Id);
        task2Record.setStatus("SUCCESS");
        task2Record.setLogOutput("Kafka installed on host-2 successfully");
        when(taskRepository.findById(task2Id)).thenReturn(Optional.of(task2Record));

        when(deploymentService.deleteClusterFromHost(eq(clusterId), eq("host-2"), eq("3.5.1"), any()))
                .thenReturn(cleanupTaskId2);
        Task cleanupTask2 = new Task();
        cleanupTask2.setId(cleanupTaskId2);
        cleanupTask2.setStatus("SUCCESS");
        when(taskAwaiter.await(cleanupTaskId2)).thenReturn(cleanupTask2);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Host-1 network timeout");

        verify(jobService).failStep(step1.getId(), "Host-1 network timeout");
        verify(jobService).completeStep(step2.getId(), "Kafka installed on host-2 successfully");
        verify(deploymentService).deleteClusterFromHost(eq(clusterId), eq("host-2"), eq("3.5.1"), any());
        verify(jobService).rolledBackStep(eq(step2.getId()), any());
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void cancellationUnsupportedWhenTaskInProgressDoesNotMakeFalseCancelledClaim() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        when(taskAwaiter.await(task1Id)).thenThrow(new RuntimeException("Crash on host-1"));

        Task task2Record = new Task();
        task2Record.setId(task2Id);
        task2Record.setStatus("IN_PROGRESS");
        when(taskRepository.findById(task2Id)).thenReturn(Optional.of(task2Record));

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Crash on host-1");

        ArgumentCaptor<String> step2Reason = ArgumentCaptor.forClass(String.class);
        verify(jobService).failStep(eq(step2.getId()), step2Reason.capture());
        assertThat(step2Reason.getValue()).contains("remote task cancellation unsupported");
        assertThat(step2Reason.getValue()).doesNotContain("task cancelled before agent execution");
        verify(taskRepository, never()).save(any());
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void compensationFailureRecordsRollbackFailureAndRetainsOriginalDeploymentFailure() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID cleanupTaskId1 = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        Task task1 = new Task();
        task1.setId(task1Id);
        task1.setStatus("SUCCESS");
        when(taskAwaiter.await(task1Id)).thenReturn(task1);

        when(taskAwaiter.await(task2Id)).thenThrow(new RuntimeException("Root deploy failure on host-2"));

        when(deploymentService.deleteClusterFromHost(any(), any(), any(), any()))
                .thenReturn(cleanupTaskId1);
        when(taskAwaiter.await(cleanupTaskId1)).thenThrow(new RuntimeException("Host-1 deletion failed"));

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class)
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Root deploy failure on host-2");

        verify(jobService).rollbackFailedStep(step1.getId(), "Host-1 deletion failed");
        verify(jobService).failStep(step2.getId(), "Root deploy failure on host-2");
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void activeTaskSucceedsRemotelyAfterAwaitFailureIsReconciledAndCompensated() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID cleanupTaskId1 = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        when(taskAwaiter.await(task1Id)).thenThrow(new RuntimeException("Connection reset during await"));

        Task task1Record = new Task();
        task1Record.setId(task1Id);
        task1Record.setStatus("SUCCESS");
        task1Record.setLogOutput("Kafka started on host-1");
        when(taskRepository.findById(task1Id)).thenReturn(Optional.of(task1Record));

        Task task2Record = new Task();
        task2Record.setId(task2Id);
        task2Record.setStatus("PENDING");
        when(taskRepository.findById(task2Id)).thenReturn(Optional.of(task2Record));
        when(taskRepository.cancelPendingTask(eq(task2Id), any())).thenReturn(1);

        when(deploymentService.deleteClusterFromHost(eq(clusterId), eq("host-1"), eq("3.5.1"), any()))
                .thenReturn(cleanupTaskId1);
        Task cleanupTask1 = new Task();
        cleanupTask1.setId(cleanupTaskId1);
        cleanupTask1.setStatus("SUCCESS");
        cleanupTask1.setLogOutput("Deleted cluster from host-1");
        when(taskAwaiter.await(cleanupTaskId1)).thenReturn(cleanupTask1);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Connection reset during await");

        verify(jobService).completeStep(step1.getId(), "Kafka started on host-1");
        verify(jobService, never()).failStep(eq(step1.getId()), any());
        verify(deploymentService).deleteClusterFromHost(eq(clusterId), eq("host-1"), eq("3.5.1"), any());
        verify(jobService).rolledBackStep(step1.getId(), "Deleted cluster from host-1");
        verify(taskRepository).cancelPendingTask(eq(task2Id), contains("Cancelled due to batch deployment failure"));
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void activeTaskRemainsInProgressAfterAwaitFailureDoesNotAssumeSuccessOrCancellation() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id);

        when(taskAwaiter.await(task1Id)).thenThrow(new RuntimeException("SocketTimeoutException awaiting task"));

        Task task1Record = new Task();
        task1Record.setId(task1Id);
        task1Record.setStatus("IN_PROGRESS");
        when(taskRepository.findById(task1Id)).thenReturn(Optional.of(task1Record));

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SocketTimeoutException awaiting task");

        ArgumentCaptor<String> failReason = ArgumentCaptor.forClass(String.class);
        verify(jobService).failStep(eq(step1.getId()), failReason.capture());
        assertThat(failReason.getValue()).contains("remote task cancellation unsupported");
        verify(jobService, never()).completeStep(eq(step1.getId()), any());
        verify(jobService, never()).rolledBackStep(eq(step1.getId()), any());
        verify(deploymentService, never()).deleteClusterFromHost(any(), any(), any(), any());
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void activeTaskConfirmedFailedOrCancelledRecordsCorrectStepOutcome() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id);

        when(taskAwaiter.await(task1Id)).thenThrow(new RuntimeException("Await dropped"));

        Task task1Record = new Task();
        task1Record.setId(task1Id);
        task1Record.setStatus("FAILED");
        task1Record.setErrorMsg("Port 9092 already in use");
        when(taskRepository.findById(task1Id)).thenReturn(Optional.of(task1Record));

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Await dropped");

        verify(jobService).failStep(eq(step1.getId()), eq("Remote agent task failed: Port 9092 already in use"));
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void pendingTaskClaimedConcurrentlyReReadsStateAndHandlesHonestly() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id);

        when(taskAwaiter.await(task1Id)).thenThrow(new RuntimeException("Crash on host-1"));

        Task initialTask2 = new Task();
        initialTask2.setId(task2Id);
        initialTask2.setStatus("PENDING");

        Task claimedTask2 = new Task();
        claimedTask2.setId(task2Id);
        claimedTask2.setStatus("IN_PROGRESS");

        // Initially found as PENDING, re-read returns claimed IN_PROGRESS
        when(taskRepository.findById(task2Id)).thenReturn(Optional.of(initialTask2), Optional.of(claimedTask2));
        // Atomic cancel fails because agent claimed it concurrently (0 rows updated)
        when(taskRepository.cancelPendingTask(eq(task2Id), any())).thenReturn(0);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Crash on host-1");

        verify(taskRepository).cancelPendingTask(eq(task2Id), any());
        ArgumentCaptor<String> step2FailReason = ArgumentCaptor.forClass(String.class);
        verify(jobService).failStep(eq(step2.getId()), step2FailReason.capture());
        assertThat(step2FailReason.getValue()).contains("remote task cancellation unsupported");
        assertThat(step2FailReason.getValue()).doesNotContain("task cancelled before agent execution");
        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }

    @Test
    void stepSucceedingDuringFailureHandlingIsCompensatedExactlyOnceInReverseOrder() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("PENDING");

        Job job = new Job();
        job.setId(UUID.randomUUID());
        job.setType(JobType.DEPLOYMENT);
        job.setPayload("{\"clusterId\":\"" + clusterId + "\",\"kafkaVersion\":\"3.5.1\"}");

        JobStep step1 = new JobStep();
        step1.setId(UUID.randomUUID());
        step1.setName("Deploy Node 1");
        step1.setTargetId("host-1");
        step1.setStatus(JobStepStatus.PENDING);
        step1.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-1\",\"node_id\":\"1\",\"role\":\"broker\"}");

        JobStep step2 = new JobStep();
        step2.setId(UUID.randomUUID());
        step2.setName("Deploy Node 2");
        step2.setTargetId("host-2");
        step2.setStatus(JobStepStatus.PENDING);
        step2.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-2\",\"node_id\":\"2\",\"role\":\"broker\"}");

        JobStep step3 = new JobStep();
        step3.setId(UUID.randomUUID());
        step3.setName("Deploy Node 3");
        step3.setTargetId("host-3");
        step3.setStatus(JobStepStatus.PENDING);
        step3.setPayload("{\"operation\":\"deploy\",\"host_id\":\"host-3\",\"node_id\":\"3\",\"role\":\"broker\"}");

        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID task3Id = UUID.randomUUID();
        UUID cleanup1Id = UUID.randomUUID();
        UUID cleanup2Id = UUID.randomUUID();
        UUID cleanup3Id = UUID.randomUUID();

        JobService jobService = mock(JobService.class);
        DeploymentService deploymentService = mock(DeploymentService.class);
        AgentTaskAwaiter taskAwaiter = mock(AgentTaskAwaiter.class);
        ClusterRepository clusterRepository = mock(ClusterRepository.class);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(clusterRepository.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(jobService.getSteps(job.getId())).thenReturn(List.of(step1, step2, step3));

        when(deploymentService.deployKafkaToHost(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(task1Id, task2Id, task3Id);

        // Step 1 completed normally
        Task task1 = new Task();
        task1.setId(task1Id);
        task1.setStatus("SUCCESS");
        task1.setLogOutput("Node 1 ok");
        when(taskAwaiter.await(task1Id)).thenReturn(task1);

        // Step 2 await failed
        when(taskAwaiter.await(task2Id)).thenThrow(new RuntimeException("Await dropped on host-2"));

        // In failure handling: Task 2 actually succeeded remotely
        Task task2Record = new Task();
        task2Record.setId(task2Id);
        task2Record.setStatus("SUCCESS");
        task2Record.setLogOutput("Node 2 ok");
        when(taskRepository.findById(task2Id)).thenReturn(Optional.of(task2Record));

        // Task 3 also completed remotely
        Task task3Record = new Task();
        task3Record.setId(task3Id);
        task3Record.setStatus("SUCCESS");
        task3Record.setLogOutput("Node 3 ok");
        when(taskRepository.findById(task3Id)).thenReturn(Optional.of(task3Record));

        when(deploymentService.deleteClusterFromHost(eq(clusterId), eq("host-3"), any(), any())).thenReturn(cleanup3Id);
        when(deploymentService.deleteClusterFromHost(eq(clusterId), eq("host-2"), any(), any())).thenReturn(cleanup2Id);
        when(deploymentService.deleteClusterFromHost(eq(clusterId), eq("host-1"), any(), any())).thenReturn(cleanup1Id);

        Task cleanupTask = new Task();
        cleanupTask.setStatus("SUCCESS");
        cleanupTask.setLogOutput("Cleaned");
        when(taskAwaiter.await(cleanup3Id)).thenReturn(cleanupTask);
        when(taskAwaiter.await(cleanup2Id)).thenReturn(cleanupTask);
        when(taskAwaiter.await(cleanup1Id)).thenReturn(cleanupTask);

        DeploymentJobHandler handler = new DeploymentJobHandler(
                jobService,
                deploymentService,
                taskAwaiter,
                clusterRepository,
                mock(HostRepository.class),
                new ObjectMapper(),
                mock(PrometheusMonitoringService.class),
                mock(KafkaAdminService.class),
                mock(DataServiceConnectionService.class),
                mock(ClusterServiceAssignmentRepository.class),
                taskRepository
        );

        assertThatThrownBy(() -> handler.execute(job))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Await dropped on host-2");

        // Verify all 3 steps compensated exactly once
        verify(deploymentService, times(1)).deleteClusterFromHost(eq(clusterId), eq("host-3"), any(), any());
        verify(deploymentService, times(1)).deleteClusterFromHost(eq(clusterId), eq("host-2"), any(), any());
        verify(deploymentService, times(1)).deleteClusterFromHost(eq(clusterId), eq("host-1"), any(), any());

        // Verify reverse completion order: host-3 -> host-2 -> host-1
        InOrder inOrder = inOrder(deploymentService);
        inOrder.verify(deploymentService).deleteClusterFromHost(eq(clusterId), eq("host-3"), any(), any());
        inOrder.verify(deploymentService).deleteClusterFromHost(eq(clusterId), eq("host-2"), any(), any());
        inOrder.verify(deploymentService).deleteClusterFromHost(eq(clusterId), eq("host-1"), any(), any());

        assertThat(cluster.getStatus()).isEqualTo("FAILED");
    }
}
