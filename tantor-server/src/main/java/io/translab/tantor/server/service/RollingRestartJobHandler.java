package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.repository.ClusterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

@Component
@RequiredArgsConstructor
public class RollingRestartJobHandler implements JobHandler {

    private final JobService jobService;
    private final DeploymentService deploymentService;
    private final AgentTaskAwaiter taskAwaiter;
    private final ClusterRepository clusterRepository;
    private final KafkaAdminService kafkaAdminService;
    private final RollingRestartHealthService healthService;
    private final ExternalClusterService externalClusterService;
    private final ObjectMapper objectMapper;

    @Value("${tantor.rolling-restart.health-timeout-seconds:300}")
    private long healthTimeoutSeconds;
    @Value("${tantor.rolling-restart.health-poll-seconds:10}")
    private long healthPollSeconds;

    private LongSupplier nanoClock = System::nanoTime;
    private HealthSleeper healthSleeper = Thread::sleep;

    @FunctionalInterface
    interface HealthSleeper { void sleep(long millis) throws InterruptedException; }

    @Override
    public boolean supports(JobType type) {
        return type == JobType.ROLLING_RESTART;
    }

    @Override
    public void execute(Job job) {
        Map<String, Object> jobPayload = readMap(job.getPayload());
        UUID clusterId = UUID.fromString(String.valueOf(jobPayload.get("clusterId")));
        Cluster cluster = clusterRepository.findWithServicesById(clusterId)
                .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));
        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            executeExternal(job, clusterId);
            return;
        }
        List<ClusterServiceAssignment> services = cluster.getServices() == null
                ? List.of()
                : new ArrayList<>(cluster.getServices());
        long brokerCount = services.stream().filter(this::isBroker).count();
        long metadataCount = services.stream().filter(service -> "zookeeper".equalsIgnoreCase(cluster.getMode())
                ? "zookeeper".equals(service.getRole()) : isController(service)).count();
        long uniqueHosts = services.stream().map(ClusterServiceAssignment::getHostId).distinct().count();
        Set<String> requiredHostIds = services.stream().map(ClusterServiceAssignment::getHostId)
                .filter(hostId -> hostId != null && !hostId.isBlank()).collect(java.util.stream.Collectors.toSet());
        boolean confirmedSingleNode = Boolean.parseBoolean(String.valueOf(jobPayload.getOrDefault("confirmSingleNode", false)));
        boolean singleNodeOverride = uniqueHosts == 1 && confirmedSingleNode;
        if (!singleNodeOverride && brokerCount < 2) throw new RuntimeException("Rolling restart requires at least two brokers.");
        if (!singleNodeOverride && metadataCount < 3) throw new RuntimeException("Rolling restart requires at least three controller or ZooKeeper quorum nodes.");

        Integer activeController = "kraft".equalsIgnoreCase(cluster.getMode())
                ? kafkaAdminService.getControllerId(clusterId) : null;
        List<JobStep> steps = new ArrayList<>(jobService.getSteps(job.getId()));
        steps.sort(Comparator
                .comparingInt((JobStep step) -> phase(readMap(step.getPayload()).get("role").toString(), cluster.getMode()))
                .thenComparingInt(step -> activeController != null
                        && activeController.toString().equals(String.valueOf(readMap(step.getPayload()).get("nodeId"))) ? 1 : 0)
                .thenComparing(JobStep::getStepOrder));

        waitForHealth(clusterId, (int) brokerCount, requiredHostIds);
        for (JobStep step : steps) {
            if (step.getStatus() == JobStepStatus.SUCCESS) continue;
            Map<String, Object> payload = readMap(step.getPayload());
            try {
                if (step.getStatus() == JobStepStatus.IN_PROGRESS && step.getAgentTaskId() != null) {
                    Task completed = taskAwaiter.await(step.getAgentTaskId());
                    waitForHealth(clusterId, (int) brokerCount, requiredHostIds);
                    jobService.completeStep(step.getId(), completed.getLogOutput());
                    continue;
                }
                jobService.startStep(step.getId());
                UUID taskId = deploymentService.restartService(
                        clusterId,
                        payload.get("hostId").toString(),
                        systemdServiceName(payload.get("role").toString())
                );
                jobService.attachAgentTask(step.getId(), taskId);
                Task task = taskAwaiter.await(taskId);
                waitForHealth(clusterId, (int) brokerCount, requiredHostIds);
                jobService.completeStep(step.getId(), task.getLogOutput());
            } catch (Exception e) {
                jobService.failStep(step.getId(), e.getMessage());
                clusterRepository.findById(clusterId).ifPresent(c -> {
                    c.setStatus("FAILED");
                    clusterRepository.save(c);
                });
                throw e;
            }
        }
        clusterRepository.findById(clusterId).ifPresent(c -> {
            c.setStatus("SUCCESS");
            clusterRepository.save(c);
        });
    }

    @Override
    public void rollback(Job job) {
        UUID clusterId = UUID.fromString(String.valueOf(readMap(job.getPayload()).get("clusterId")));
        Cluster cluster = clusterRepository.findWithServicesById(clusterId)
                .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));
        int brokerCount = (int) cluster.getServices().stream().filter(this::isBroker).count();
        Set<String> requiredHostIds = cluster.getServices().stream().map(ClusterServiceAssignment::getHostId)
                .filter(hostId -> hostId != null && !hostId.isBlank()).collect(java.util.stream.Collectors.toSet());
        waitForHealth(clusterId, brokerCount, requiredHostIds);
        for (JobStep step : jobService.getSteps(job.getId())) {
            if (step.getStatus() == JobStepStatus.SUCCESS || step.getStatus() == JobStepStatus.ROLLBACK_FAILED) {
                jobService.rolledBackStep(step.getId(), "Rolling restart changed no desired configuration; cluster health was verified.");
            }
        }
    }

    private void waitForHealth(UUID clusterId, int expectedBrokers, Set<String> requiredHostIds) {
        long deadline = nanoClock.getAsLong() + TimeUnit.SECONDS.toNanos(Math.max(1, healthTimeoutSeconds));
        RollingRestartHealthService.HealthSnapshot last = null;
        while (deadline - nanoClock.getAsLong() > 0) {
            long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - nanoClock.getAsLong()));
            last = healthService.inspect(clusterId, expectedBrokers, requiredHostIds, remainingMillis);
            if (last.healthy()) return;
            long remainingNanos = deadline - nanoClock.getAsLong();
            if (remainingNanos <= 0) break;
            try {
                long sleepMillis = Math.min(TimeUnit.SECONDS.toMillis(Math.max(1, healthPollSeconds)),
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
                healthSleeper.sleep(sleepMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while waiting for cluster health", e);
            }
        }
        throw new RuntimeException("Timed out waiting for cluster health: "
                + (last == null ? "no health sample available" : last.failureReason()));
    }

    private void executeExternal(Job job, UUID clusterId) {
        JobStep step = jobService.getSteps(job.getId()).stream().findFirst()
                .orElseThrow(() -> new RuntimeException("External restart job has no step."));
        if (step.getStatus() == JobStepStatus.SUCCESS) return;
        jobService.startStep(step.getId());
        try {
            Map<String, Object> queued = externalClusterService.queueRestart(clusterId);
            String taskId = String.valueOf(queued.get("taskId"));
            for (int attempt = 0; attempt < 120; attempt++) {
                Map<String, Object> statusMap = externalClusterService.getExternalTaskStatus(taskId);
                String status = String.valueOf(statusMap.get("status"));
                if ("SUCCESS".equals(status)) {
                    jobService.completeStep(step.getId(), "Restart task completed successfully");
                    return;
                }
                if ("FAILED".equals(status)) throw new RuntimeException(String.valueOf(statusMap.get("message")));
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while waiting for external restart", e);
                }
            }
            throw new RuntimeException("Timed out waiting for external restart.");
        } catch (Exception e) {
            jobService.failStep(step.getId(), e.getMessage());
            throw e;
        }
    }

    private int phase(String role, String mode) {
        if ("zookeeper".equalsIgnoreCase(mode)) return "zookeeper".equals(role) ? 0 : 1;
        if ("controller".equals(role)) return 0;
        if ("broker_controller".equals(role)) return 1;
        return 2;
    }

    private boolean isBroker(ClusterServiceAssignment service) {
        return List.of("broker", "broker_controller", "broker_zookeeper").contains(service.getRole());
    }

    private boolean isController(ClusterServiceAssignment service) {
        return "controller".equals(service.getRole()) || "broker_controller".equals(service.getRole());
    }

    private String systemdServiceName(String role) {
        if ("controller".equals(role)) return "controller";
        if ("zookeeper".equals(role)) return "zookeeper";
        if ("broker_controller".equals(role) || "broker_zookeeper".equals(role)) return "kafka";
        return "broker";
    }

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid rolling restart payload", e);
        }
    }
}
