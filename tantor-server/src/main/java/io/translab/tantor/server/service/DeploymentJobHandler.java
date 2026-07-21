package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class DeploymentJobHandler implements JobHandler {

    private final JobService jobService;
    private final DeploymentService deploymentService;
    private final AgentTaskAwaiter taskAwaiter;
    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final ObjectMapper objectMapper;
    private final PrometheusMonitoringService prometheusMonitoringService;

    @Override
    public boolean supports(JobType type) {
        return type == JobType.DEPLOYMENT || type == JobType.ADD_HOST;
    }

    @Override
    public void execute(Job job) {
        Map<String, Object> jobPayload = readMap(job.getPayload());
        UUID clusterId = UUID.fromString(String.valueOf(jobPayload.get("clusterId")));
        Cluster cluster = clusterRepository.findById(clusterId)
                .orElseThrow(() -> new RuntimeException("Cluster not found: " + clusterId));
        cluster.setStatus("RUNNING");
        clusterRepository.save(cluster);

        List<JobStep> steps = jobService.getSteps(job.getId());
        if (steps.isEmpty()) throw new RuntimeException("Deployment job has no persisted steps.");

        for (int i = 0; i < steps.size();) {
            JobStep step = steps.get(i);
            if (step.getStatus() == JobStepStatus.SUCCESS) {
                i++;
                continue;
            }
            Map<String, Object> payload = readMap(step.getPayload());
            if (isDeployOperation(payload)) {
                List<JobStep> batchSteps = new ArrayList<>();
                List<Map<String, Object>> batchPayloads = new ArrayList<>();
                while (i < steps.size()) {
                    JobStep candidate = steps.get(i);
                    if (candidate.getStatus() == JobStepStatus.SUCCESS) {
                        i++;
                        continue;
                    }
                    Map<String, Object> candidatePayload = readMap(candidate.getPayload());
                    if (!isDeployOperation(candidatePayload)) break;
                    batchSteps.add(candidate);
                    batchPayloads.add(candidatePayload);
                    i++;
                }
                executeDeployBatch(job, cluster, clusterId, jobPayload, batchSteps, batchPayloads);
                continue;
            }
            try {
                if (step.getStatus() == JobStepStatus.IN_PROGRESS && step.getAgentTaskId() != null) {
                    Task completed = taskAwaiter.await(step.getAgentTaskId());
                    jobService.completeStep(step.getId(), taskOutput(completed));
                    i++;
                    continue;
                }
                jobService.startStep(step.getId());
                UUID taskId = startOperation(clusterId, jobPayload, payload);
                jobService.attachAgentTask(step.getId(), taskId);
                Task completed = taskAwaiter.await(taskId);
                jobService.completeStep(step.getId(), taskOutput(completed));
                jobService.appendLog(job.getId(), step.getName() + " completed on " + step.getTargetId() + ".");
            } catch (Exception e) {
                jobService.failStep(step.getId(), e.getMessage());
                cluster.setStatus("FAILED");
                clusterRepository.save(cluster);
                throw e;
            }
            i++;
        }

        cluster.setStatus("SUCCESS");
        clusterRepository.save(cluster);
    }

    private void executeDeployBatch(
            Job job,
            Cluster cluster,
            UUID clusterId,
            Map<String, Object> jobPayload,
            List<JobStep> steps,
            List<Map<String, Object>> payloads
    ) {
        List<RunningStep> running = new ArrayList<>();
        JobStep activeStep = null;
        try {
            for (int i = 0; i < steps.size(); i++) {
                JobStep step = steps.get(i);
                activeStep = step;
                UUID taskId = step.getAgentTaskId();
                if (step.getStatus() != JobStepStatus.IN_PROGRESS || taskId == null) {
                    jobService.startStep(step.getId());
                    taskId = startOperation(clusterId, jobPayload, payloads.get(i));
                    jobService.attachAgentTask(step.getId(), taskId);
                    jobService.appendLog(job.getId(), step.getName() + " dispatched to " + step.getTargetId() + ".");
                }
                running.add(new RunningStep(step, taskId));
            }

            for (RunningStep runningStep : running) {
                activeStep = runningStep.step();
                Task completed = taskAwaiter.await(runningStep.taskId());
                jobService.completeStep(activeStep.getId(), taskOutput(completed));
                jobService.appendLog(job.getId(), activeStep.getName() + " completed on " + activeStep.getTargetId() + ".");
            }
        } catch (Exception e) {
            if (activeStep != null) {
                jobService.failStep(activeStep.getId(), e.getMessage());
            }
            cluster.setStatus("FAILED");
            clusterRepository.save(cluster);
            throw e;
        }
    }

    @Override
    public void rollback(Job job) {
        Map<String, Object> jobPayload = readMap(job.getPayload());
        UUID clusterId = UUID.fromString(String.valueOf(jobPayload.get("clusterId")));
        List<JobStep> steps = new ArrayList<>(jobService.getSteps(job.getId()));
        Collections.reverse(steps);

        for (JobStep step : steps) {
            if (step.getStatus() != JobStepStatus.SUCCESS
                    && step.getStatus() != JobStepStatus.ROLLBACK_FAILED
                    && step.getStatus() != JobStepStatus.IN_PROGRESS) continue;
            Map<String, Object> payload = readMap(step.getPayload());
            try {
				String operation = operation(payload);
				if (!"deploy".equals(operation)) {
					if (step.getStatus() == JobStepStatus.IN_PROGRESS && step.getAgentTaskId() != null) {
						taskAwaiter.await(step.getAgentTaskId());
					}
					jobService.rolledBackStep(step.getId(), "Validation step has no deployed resource to remove.");
					continue;
				}
                if (step.getStatus() == JobStepStatus.IN_PROGRESS && step.getAgentTaskId() != null) {
                    Task completed = taskAwaiter.await(step.getAgentTaskId());
                    jobService.rolledBackStep(step.getId(), taskOutput(completed));
                    continue;
                }
                jobService.startStep(step.getId());
                UUID taskId = deploymentService.deleteClusterFromHost(
                        clusterId,
                        required(payload, "host_id"),
                        required(jobPayload, "kafkaVersion"),
                        stringValue(payload, "serviceConfigJson")
                );
                jobService.attachAgentTask(step.getId(), taskId);
                Task completed = taskAwaiter.await(taskId);
                jobService.rolledBackStep(step.getId(), taskOutput(completed));
            } catch (Exception e) {
                jobService.rollbackFailedStep(step.getId(), e.getMessage());
                throw e;
            }
        }
        finalizeRollback(job, clusterId);
    }

    protected void finalizeRollback(Job job, UUID clusterId) {
        Cluster cluster = clusterRepository.findWithServicesById(clusterId).orElse(null);
        if (cluster == null) return;

        if (job.getType() == JobType.ADD_HOST) {
            List<Map<String, Object>> rolledBackTargets = jobService.getSteps(job.getId()).stream()
                    .filter(step -> step.getStatus() == JobStepStatus.ROLLED_BACK)
                    .map(step -> readMap(step.getPayload()))
                    .toList();
            if (cluster.getServices() != null) {
                cluster.getServices().removeIf(service -> rolledBackTargets.stream().anyMatch(target ->
                        required(target, "host_id").equals(service.getHostId())
                                && required(target, "node_id").equals(String.valueOf(service.getNodeId()))));
            }
            for (Map<String, Object> target : rolledBackTargets) {
                hostRepository.findById(required(target, "host_id")).ifPresent(host -> {
                    if (clusterId.equals(host.getClusterId())) {
                        host.setClusterId(null);
                        hostRepository.save(host);
                    }
                });
            }
            cluster.setStatus("SUCCESS");
        } else {
            if (cluster.getServices() != null) {
                cluster.getServices().forEach(service -> hostRepository.findById(service.getHostId()).ifPresent(host -> {
                    if (clusterId.equals(host.getClusterId())) {
                        host.setClusterId(null);
                        hostRepository.save(host);
                    }
                }));
            }
            cluster.setStatus("ROLLED_BACK");
        }
        clusterRepository.save(cluster);
    }

    private UUID startOperation(UUID clusterId, Map<String, Object> jobPayload, Map<String, Object> payload) {
        return switch (operation(payload)) {
            case "connectivity" -> deploymentService.checkKRaftConnectivity(
                    clusterId,
                    required(payload, "host_id"),
                    required(payload, "controller_endpoints")
            );
            case "verify_quorum" -> deploymentService.verifyKRaftQuorum(
                    clusterId,
                    required(payload, "host_id"),
                    required(payload, "controller_endpoints"),
                    required(payload, "cluster_uuid"),
                    required(payload, "expected_controller_count"),
                    stringValue(payload, "serviceConfigJson")
            );
            case "verify_zk_quorum" -> deploymentService.verifyZooKeeperQuorum(
                    clusterId,
                    required(payload, "host_id"),
                    required(payload, "zookeeper_connect"),
                    stringValue(payload, "serviceConfigJson")
            );
            case "deploy" -> deploymentService.deployKafkaToHost(
                    clusterId,
                    required(payload, "host_id"),
                    required(jobPayload, "kafkaVersion"),
                    stringValue(jobPayload, "finalArtifactUrl"),
                    "",
                    required(payload, "node_id"),
                    stringValue(jobPayload, "quorumVoters"),
                    required(payload, "role"),
                    stringValue(payload, "serviceConfigJson")
            );
            default -> throw new IllegalArgumentException("Unknown deployment job operation: " + operation(payload));
        };
    }

    private String operation(Map<String, Object> payload) {
        String value = stringValue(payload, "operation");
        return value.isBlank() ? "deploy" : value;
    }

    private boolean isDeployOperation(Map<String, Object> payload) {
        return "deploy".equals(operation(payload));
    }

    private record RunningStep(JobStep step, UUID taskId) {}

    private Map<String, Object> readMap(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid job payload", e);
        }
    }

    private String required(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("Missing job payload field: " + key);
        }
        return String.valueOf(value);
    }

    private String stringValue(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String taskOutput(Task task) {
        String logs = task.getLogOutput() == null ? "" : task.getLogOutput();
        return logs.isBlank() ? "Agent task " + task.getId() + " completed successfully." : logs;
    }
}
