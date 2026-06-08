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
    private final ObjectMapper objectMapper;

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
        } else if (!"PENDING".equals(host.getStatus())) {
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
            if (!"PENDING".equals(host.getStatus())) {
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
            });
        } catch (IllegalArgumentException e) {
            log.error("Invalid task ID format: {}", dto.getTaskId(), e);
        }
    }
}
