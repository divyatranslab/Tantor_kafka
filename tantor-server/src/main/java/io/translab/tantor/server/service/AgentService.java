package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.dto.HostHeartbeatDto;
import io.translab.tantor.server.dto.HostRegistrationDto;
import io.translab.tantor.server.dto.TaskDto;
import io.translab.tantor.server.dto.TaskResultDto;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
    private final ActivityAlertService activityAlertService;
    private final AuditService auditService;

    @Value("${tantor.hosts.heartbeat-timeout-seconds:90}")
    private long heartbeatTimeoutSeconds;

    public enum HeartbeatResult {
        ACCEPTED,
        NOT_FOUND,
        SOURCE_MISMATCH
    }

    @Transactional
    public boolean registerHost(HostRegistrationDto dto, String sourceIp) {
        Host existing = hostRepository.findById(dto.getHostId()).orElse(null);
        if (existing != null && !Boolean.TRUE.equals(existing.getRemoved()) && !sourceMatches(existing, sourceIp) && !isHeartbeatStale(existing)) {
            log.warn("Rejected duplicate registration for host {} from {}. Registered host IP is {}.",
                    dto.getHostId(), sourceIp, existing.getHostIp());
            return false;
        }
        Map<String, Object> oldValue = existing == null ? Map.of() : Map.of(
                "hostname", String.valueOf(existing.getHostname()),
                "status", String.valueOf(existing.getStatus()),
                "agentVersion", String.valueOf(existing.getAgentVersion()));
        Host host = existing == null ? new Host() : existing;
        host.setId(dto.getHostId());
        host.setHostname(dto.getHostname());
        List<String> selectedIps = selectHostIp(dto.getIpAddresses());
        if (!selectedIps.isEmpty()) {
            host.setHostIp(selectedIps.get(0));
        }
        try {
            host.setIpAddresses(objectMapper.writeValueAsString(selectedIps));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize IPs for host {}", dto.getHostId(), e);
        }
        String selectedHostIp = selectedIps.isEmpty() ? sourceIp : selectedIps.get(0);
        String agentName = firstNonBlank(dto.getAgentName(), host.getAgentName(), dto.getHostname(), dto.getHostId());
        String agentPath = firstNonBlank(dto.getAgentPath(), host.getAgentPath(), "/srv/tantor-agent/tantor-agent-linux");
        String auditActor = selectedHostIp == null || selectedHostIp.isBlank()
                ? agentName
                : agentName + " (" + selectedHostIp + ")";
        host.setResourceType("HOST");
        host.setUser(auditActor);
        host.setRemoved(false);
        host.setAction(existing == null ? "HOST_REGISTERED" : "HOST_UPDATED");
        host.setOsDetails(dto.getOsDetails());
        host.setAgentVersion(dto.getAgentVersion());
        host.setAgentName(agentName);
        host.setAgentPath(agentPath);
        host.setAgentStatus("ONLINE");
        if (host.getStatus() == null) {
            host.setStatus("PENDING");
        } else if (!"PENDING".equals(host.getStatus()) && !"OCCUPIED".equalsIgnoreCase(host.getStatus())) {
            host.setStatus("ONLINE");
        }
        host.setLastHeartbeat(OffsetDateTime.now());
        
        hostRepository.save(host);
        log.info("Registered host: {}", dto.getHostId());
        activityAlertService.logAudit("INFO", "AGENT", existing != null ? "RECONNECT" : "REGISTER",
                existing != null ? "Agent reconnected" : "Agent registered", "HOST", dto.getHostId(), host.getClusterId(),
                null, host.getStatus(), "SUCCESS", existing != null ? null : "PENDING", "agentVersion=" + dto.getAgentVersion());
        return true;
    }

    @Transactional
    public void registerHost(HostRegistrationDto dto) {
        registerHost(dto, null);
    }

    @Transactional
    public HeartbeatResult processHeartbeat(HostHeartbeatDto dto, String sourceIp) {
        return hostRepository.findById(dto.getHostId()).map(host -> {
            if (!sourceMatches(host, sourceIp)) {
                if (isHeartbeatStale(host)) {
                    log.warn("Host {} heartbeat from {} does not match registered IP {}, but the registered heartbeat is stale. Requesting re-registration.",
                            dto.getHostId(), sourceIp, host.getHostIp());
                    return HeartbeatResult.NOT_FOUND;
                }
                log.warn("Rejected heartbeat for host {} from {}. Registered host IP is {}.",
                        dto.getHostId(), sourceIp, host.getHostIp());
                return HeartbeatResult.SOURCE_MISMATCH;
            }
            host.setCpuUsagePct(dto.getCpuUsagePct());
            host.setMemTotalMb(dto.getMemTotalMb());
            host.setMemUsedMb(dto.getMemUsedMb());
            host.setDiskTotalGb(dto.getDiskTotalGb());
            host.setDiskUsedGb(dto.getDiskUsedGb());
            host.setJavaVersion(dto.getJavaVersion());
            host.setLastHeartbeat(OffsetDateTime.now());
            host.setAgentStatus("ONLINE");
            host.setRemoved(false);
            if (!"PENDING".equals(host.getStatus()) && !"OCCUPIED".equalsIgnoreCase(host.getStatus())) {
                host.setStatus("ONLINE");
            }
            hostRepository.save(host);
            log.debug("Processed heartbeat for host: {}", dto.getHostId());
            return HeartbeatResult.ACCEPTED;
        }).orElse(HeartbeatResult.NOT_FOUND);
    }

    @Transactional
    public boolean processHeartbeat(HostHeartbeatDto dto) {
        return processHeartbeat(dto, null) == HeartbeatResult.ACCEPTED;
    }

    private List<String> selectHostIp(List<String> addresses) {
        if (addresses == null) return List.of();
        String selected = addresses.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::trim)
                .filter(ip -> ip.matches("^(?:\\d{1,3}\\.){3}\\d{1,3}$"))
                .filter(ip -> !ip.startsWith("127.") && !ip.startsWith("169.254."))
                .sorted(java.util.Comparator.comparingInt(this::ipPriority))
                .findFirst().orElse(null);
        return selected == null ? List.of() : List.of(selected);
    }

    private int ipPriority(String ip) {
        if (ip.startsWith("192.168.")) return 0;
        if (ip.startsWith("10.")) return 1;
        if (ip.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*")) return 2;
        return 3;
    }

    private boolean sourceMatches(Host host, String sourceIp) {
        String normalized = normalizeIp(sourceIp);
        if (normalized == null || isLoopback(normalized)) {
            return true;
        }
        if (normalized.equals(normalizeIp(host.getHostIp()))) {
            return true;
        }
        if (host.getIpAddresses() == null || host.getIpAddresses().isBlank()) {
            return host.getHostIp() == null || host.getHostIp().isBlank();
        }
        try {
            List<String> knownIps = objectMapper.readValue(host.getIpAddresses(), new TypeReference<List<String>>() {});
            return knownIps.stream().map(this::normalizeIp).anyMatch(normalized::equals);
        } catch (Exception e) {
            log.warn("Failed to parse known IPs for host {}", host.getId(), e);
            return false;
        }
    }

    private String normalizeIp(String value) {
        if (value == null) return null;
        String ip = value.trim();
        if (ip.isBlank()) return null;
        if (ip.startsWith("::ffff:")) {
            ip = ip.substring("::ffff:".length());
        }
        int portIndex = ip.lastIndexOf(':');
        if (portIndex > -1 && ip.indexOf(':') == portIndex && ip.substring(portIndex + 1).matches("\\d+")) {
            ip = ip.substring(0, portIndex);
        }
        return ip;
    }

    private boolean isLoopback(String ip) {
        return "localhost".equalsIgnoreCase(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip) || ip.startsWith("127.");
    }

    private boolean isHeartbeatStale(Host host) {
        if (host.getLastHeartbeat() == null) {
            return true;
        }
        long timeoutSeconds = Math.max(heartbeatTimeoutSeconds, 1);
        return host.getLastHeartbeat().isBefore(OffsetDateTime.now().minusSeconds(timeoutSeconds));
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
                    dto.setParameters(objectMapper.readValue(t.getParameters(), new TypeReference<Map<String, Object>>() {}));
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
                if ("IN_PROGRESS".equals(dto.getStatus()) && dto.getCurrentStep() != null) {
                    task.setCurrentStep(dto.getCurrentStep());
                    try {
                        Map<String, String> stepLogsMap = new java.util.LinkedHashMap<>();
                        if (task.getStepLogs() != null && !task.getStepLogs().isBlank()) {
                            stepLogsMap = objectMapper.readValue(task.getStepLogs(), new TypeReference<Map<String, String>>() {});
                        }
                        String existingLog = stepLogsMap.getOrDefault(dto.getCurrentStep(), "");
                        String newLog = dto.getLogOutput() != null ? dto.getLogOutput() : "";
                        if (!newLog.isEmpty()) {
                            stepLogsMap.put(dto.getCurrentStep(), existingLog + newLog);
                            task.setStepLogs(objectMapper.writeValueAsString(stepLogsMap));
                        }
                    } catch (Exception e) {
                        log.error("Failed to parse step logs", e);
                    }
                    taskRepository.save(task);
                } else {
                    task.setStatus(dto.getStatus());
                    if (dto.getLogOutput() != null) {
                        task.setLogOutput(dto.getLogOutput());
                    }
                    task.setErrorMsg(dto.getErrorMsg());
                    task.setFailedReason(dto.getFailedReason());
                    task.setCurrentStep(dto.getCurrentStep());
                    try {
                        if (dto.getCurrentStep() != null && dto.getLogOutput() != null && !dto.getLogOutput().isEmpty()) {
                            Map<String, String> stepLogsMap = new java.util.LinkedHashMap<>();
                            if (task.getStepLogs() != null && !task.getStepLogs().isBlank()) {
                                stepLogsMap = objectMapper.readValue(task.getStepLogs(), new TypeReference<Map<String, String>>() {});
                            }
                            String existingLog = stepLogsMap.getOrDefault(dto.getCurrentStep(), "");
                            stepLogsMap.put(dto.getCurrentStep(), existingLog + dto.getLogOutput());
                            task.setStepLogs(objectMapper.writeValueAsString(stepLogsMap));
                        }
                    } catch (Exception e) {}
                    
                    taskRepository.save(task);
                    log.info("Task {} completed with status: {}", taskId, dto.getStatus());
                    activityAlertService.logAudit("FAILED".equalsIgnoreCase(dto.getStatus()) ? "ERROR" : "INFO",
                            "TASK", task.getCommand(), "Task completed with status " + dto.getStatus(), "TASK", taskId.toString(),
                            task.getClusterId(), "IN_PROGRESS", dto.getStatus(), dto.getStatus(), null,
                            dto.getErrorMsg());
                    if ("CHECK_PREREQUISITES".equals(task.getCommand())
                            || "APPLY_PREREQUISITES".equals(task.getCommand())) {
                        Map<String, Object> prerequisiteDetails = new java.util.LinkedHashMap<>();
                        prerequisiteDetails.put("taskId", taskId.toString());
                        prerequisiteDetails.put("hostId", task.getHostId());
                        prerequisiteDetails.put("result", dto.getStatus());
                        if (dto.getErrorMsg() != null && !dto.getErrorMsg().isBlank()) {
                            prerequisiteDetails.put("error", dto.getErrorMsg());
                        }
                        if (dto.getFailedReason() != null && !dto.getFailedReason().isBlank()) {
                            prerequisiteDetails.put("failedReason", dto.getFailedReason());
                        }
                        String action = "APPLY_PREREQUISITES".equals(task.getCommand())
                                ? "PREREQUISITE_FIX_COMPLETED" : "PREREQUISITE_CHECK_COMPLETED";
                        auditService.recordAs("agent:" + task.getHostId(), "AGENT", null,
                                "PREREQUISITE", action, "HOST", task.getHostId(),
                                task.getClusterId(), dto.getStatus(), null, null, null, prerequisiteDetails);
                    }
                    if ("REBOOT_HOST".equals(task.getCommand())) {
                        auditService.recordAs("agent:" + task.getHostId(), "AGENT", null,
                                "RESTART", "HOST_REBOOT_SCHEDULED", "HOST", task.getHostId(), task.getClusterId(),
                                dto.getStatus(), null, null, null,
                                Map.of("taskId", taskId.toString(), "result", dto.getStatus()));
                    }
                    if ("INSTALL_KAFKA".equals(task.getCommand())) {
                        Map<String, Object> deploymentDetails = new java.util.LinkedHashMap<>();
                        deploymentDetails.put("taskId", taskId.toString());
                        deploymentDetails.put("hostId", task.getHostId());
                        deploymentDetails.put("result", dto.getStatus());
                        auditService.recordAs("agent:" + task.getHostId(), "AGENT", null,
                                "DEPLOYMENT",
                                "SUCCESS".equalsIgnoreCase(dto.getStatus()) ? "KAFKA_NODE_DEPLOYED" : "KAFKA_NODE_DEPLOYMENT_FAILED",
                                "CLUSTER", task.getClusterId() == null ? null : task.getClusterId().toString(),
                                task.getClusterId(), dto.getStatus(), null, null, null, deploymentDetails);
                        if ("SUCCESS".equalsIgnoreCase(dto.getStatus())) {
                            hostRepository.findById(task.getHostId()).ifPresent(host -> {
                                host.setStatus("OCCUPIED");
                                hostRepository.save(host);
                            });
                        }
                    }
                    parcelService.processTaskResult(task);
                    cancelPendingClusterDeploymentTasks(task);

                    if ("SUCCESS".equals(dto.getStatus())) {
                        String originalTaskId = taskParameter(task, "original_task_id");
                        if (originalTaskId != null && !originalTaskId.isBlank()) {
                            try {
                                taskRepository.findById(UUID.fromString(originalTaskId)).ifPresent(originalTask -> {
                                    if ("ROLLBACK_DEPLOYMENT".equals(task.getCommand())) {
                                        originalTask.setStatus("ROLLBACK_DONE");
                                    } else if ("DELETE_CLUSTER".equals(task.getCommand())) {
                                        originalTask.setStatus("CLEANUP_DONE");
                                    }
                                    taskRepository.save(originalTask);
                                });
                            } catch (Exception e) {
                                log.warn("Failed to update original task status", e);
                            }
                        }
                    }

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
                }
            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid task ID format: {}", dto.getTaskId(), e);
        }
    }
    private void cancelPendingClusterDeploymentTasks(Task failedTask) {
        if (failedTask.getClusterId() == null
                || !"INSTALL_KAFKA".equals(failedTask.getCommand())
                || !"FAILED".equals(failedTask.getStatus())) {
            return;
        }

        taskRepository.findByClusterIdOrderByCreatedAtDesc(failedTask.getClusterId()).stream()
                .filter(task -> !task.getId().equals(failedTask.getId()))
                .filter(task -> "INSTALL_KAFKA".equals(task.getCommand()))
                .filter(task -> "PENDING".equals(task.getStatus()))
                .forEach(task -> {
                    task.setStatus("CANCELLED");
                    task.setErrorMsg("Cancelled because another node failed during cluster deployment.");
                    taskRepository.save(task);
                    log.warn("Cancelled pending deployment task {} after failure of {}", task.getId(), failedTask.getId());
                });
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
        cluster.setUpdatedBy("system");
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
                    host.setStatus("ONLINE");
                    hostRepository.save(host);
                }
            });
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
