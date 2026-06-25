package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.dto.HostHeartbeatDto;
import io.translab.tantor.server.dto.HostRegistrationDto;
import io.translab.tantor.server.dto.TaskDto;
import io.translab.tantor.server.dto.TaskResultDto;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {
    private final HostRepository hostRepository;
    private final TaskRepository taskRepository;
    private final io.translab.tantor.server.repository.ClusterRepository clusterRepository;
    private final ObjectMapper objectMapper;
    private final ParcelService parcelService;

    @Transactional
    public void registerHost(HostRegistrationDto dto) {
        Host host = hostRepository.findById(dto.getHostId()).orElse(new Host());
        host.setId(dto.getHostId());
        host.setHostname(dto.getHostname());
        try {
            host.setIpAddresses(objectMapper.writeValueAsString(dto.getIpAddresses()));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize IPs for host {}", dto.getHostId(), e);
        }
        host.setOsDetails(dto.getOsDetails());
        host.setAgentVersion(dto.getAgentVersion());
        if (host.getStatus() == null) {
            host.setStatus("PENDING");
        } else if (!"PENDING".equals(host.getStatus()) && !"UNAVAILABLE".equalsIgnoreCase(host.getStatus())) {
            host.setStatus("ONLINE");
        }
        host.setLastHeartbeat(OffsetDateTime.now());
        
        hostRepository.save(host);
        log.info("Registered host: {}", dto.getHostId());
    }

    @Transactional
    public boolean processHeartbeat(HostHeartbeatDto dto) {
        return hostRepository.findById(dto.getHostId()).map(host -> {
            host.setCpuUsagePct(dto.getCpuUsagePct());
            host.setMemTotalMb(dto.getMemTotalMb());
            host.setMemUsedMb(dto.getMemUsedMb());
            host.setDiskTotalGb(dto.getDiskTotalGb());
            host.setDiskUsedGb(dto.getDiskUsedGb());
            host.setJavaVersion(dto.getJavaVersion());
            host.setLastHeartbeat(OffsetDateTime.now());
            if (!"PENDING".equals(host.getStatus()) && !"UNAVAILABLE".equalsIgnoreCase(host.getStatus())) {
                host.setStatus("ONLINE");
            }
            hostRepository.save(host);
            log.debug("Processed heartbeat for host: {}", dto.getHostId());
            return true;
        }).orElse(false);
    }

    @Transactional
    public List<TaskDto> getPendingTasks(String hostId) {
        List<Task> pendingTasks = taskRepository.findByHostIdAndStatusOrderByCreatedAtAsc(hostId, "PENDING");
        
        return pendingTasks.stream().map(t -> {
            t.setStatus("IN_PROGRESS");
            taskRepository.save(t);
            
            TaskDto dto = new TaskDto();
            dto.setTaskId(t.getId().toString());
            if (t.getClusterId() != null) {
                dto.setClusterId(t.getClusterId().toString());
            }
            dto.setCommand(t.getCommand());
            dto.setArtifactUrl(t.getArtifactUrl());
            dto.setChecksum(t.getChecksum());
            try {
                if (t.getParameters() != null) {
                    dto.setParameters(objectMapper.readValue(t.getParameters(), Map.class));
                }
            } catch (JsonProcessingException e) {
                log.warn("Failed to deserialize parameters for task {}", t.getId(), e);
            }
            return dto;
        }).collect(Collectors.toList());
    }

    @Transactional
    public void processTaskResult(TaskResultDto dto) {
        try {
            UUID taskId = UUID.fromString(dto.getTaskId());
            taskRepository.findById(taskId).ifPresent(task -> {
                task.setStatus(dto.getStatus());
                task.setLogOutput(dto.getLogOutput());
                task.setErrorMsg(dto.getErrorMsg());
                taskRepository.save(task);
                log.info("Task {} completed with status: {}", taskId, dto.getStatus());
                parcelService.processTaskResult(task);

                if (task.getClusterId() != null) {
                    clusterRepository.findById(task.getClusterId()).ifPresent(cluster -> updateClusterStatus(cluster, task));
                } else {
                    // Legacy tasks created before cluster_id was added can only be mapped through host assignment.
                    hostRepository.findById(task.getHostId()).ifPresent(host -> {
                        if (host.getClusterId() != null) {
                            clusterRepository.findById(host.getClusterId()).ifPresent(cluster -> updateClusterStatus(cluster, task));
                        }
                    });
                }
            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid task ID format: {}", dto.getTaskId(), e);
        }
    }

    private void updateClusterStatus(io.translab.tantor.server.domain.Cluster cluster, Task currentTask) {
        String command = currentTask.getCommand();
        String status = currentTask.getStatus();
        
        if ("FAILED".equals(status)) {
            if ("UPGRADE_KAFKA".equals(command) && upgradeRollbackCompleted(currentTask)) {
                cluster.setStatus("SUCCESS");
            } else {
                cluster.setStatus("FAILED");
            }
        } else if ("VALIDATING".equals(status)) {
            cluster.setStatus("VALIDATING");
        } else if ("RUNNING".equals(status) || "IN_PROGRESS".equals(status)) {
            cluster.setStatus("DELETE_CLUSTER".equals(command) ? "DELETING" : "RUNNING");
        } else if ("SUCCESS".equals(status)) {
            boolean allSuccess = true;
            for (io.translab.tantor.server.domain.ClusterServiceAssignment svc : cluster.getServices()) {
                List<Task> hostTasks = currentTask.getClusterId() != null
                    ? taskRepository.findByClusterIdAndHostIdAndCommandOrderByCreatedAtDesc(currentTask.getClusterId(), svc.getHostId(), command)
                    : taskRepository.findByHostIdAndCommandOrderByCreatedAtDesc(svc.getHostId(), command);
                if (hostTasks.isEmpty() || !"SUCCESS".equals(hostTasks.get(0).getStatus())) {
                    allSuccess = false;
                    break;
                }
            }
            if (allSuccess) {
                if ("DELETE_CLUSTER".equals(command)) {
                    cluster.setStatus("DELETED");
                    cluster.setDeletedAt(java.time.Instant.now());
                    releaseClusterHosts(cluster);
                } else if ("UPGRADE_KAFKA".equals(command)) {
                    String targetVersion = taskParameter(currentTask, "target_version");
                    if (targetVersion == null || targetVersion.isBlank()) {
                        targetVersion = taskParameter(currentTask, "version");
                    }
                    if (targetVersion != null && !targetVersion.isBlank()) {
                        cluster.setKafkaVersion(targetVersion);
                    }
                    cluster.setStatus("SUCCESS");
                } else {
                    cluster.setStatus("SUCCESS");
                }
            }
        }
        clusterRepository.save(cluster);
    }

    private boolean upgradeRollbackCompleted(Task task) {
        String error = task.getErrorMsg() == null ? "" : task.getErrorMsg();
        String logs = task.getLogOutput() == null ? "" : task.getLogOutput();
        return error.contains("Rollback completed") || logs.contains("Rollback completed");
    }

    @SuppressWarnings("unchecked")
    private String taskParameter(Task task, String name) {
        if (task.getParameters() == null || task.getParameters().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParameters(), Map.class);
            Object value = params.get(name);
            return value == null ? null : String.valueOf(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse task parameters for task {}", task.getId(), e);
            return null;
        }
    }

    private void releaseClusterHosts(io.translab.tantor.server.domain.Cluster cluster) {
        if (cluster.getServices() == null) {
            return;
        }
        for (io.translab.tantor.server.domain.ClusterServiceAssignment svc : cluster.getServices()) {
            hostRepository.findById(svc.getHostId()).ifPresent(host -> {
                if (cluster.getId().equals(host.getClusterId())) {
                    host.setClusterId(null);
                    hostRepository.save(host);
                }
            });
        }
    }
}
