package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.service.ExternalClusterService.ExternalAgentTask;

import io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerMetricsDto;

import io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerRecord;
import io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport;
import io.translab.tantor.server.service.ExternalClusterService.AgentTaskCompletion;
import io.translab.tantor.server.service.ExternalClusterService.BootstrapExternalClusterRequest;
import io.translab.tantor.server.domain.ExternalClusterNode;

import io.translab.tantor.server.domain.DiscoveryAgent;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.security.TruststoreStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import io.translab.tantor.server.audit.AuditService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.translab.tantor.server.repository.ExternalClusterRepository;

@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class ExternalClusterTaskService {
    private final io.translab.tantor.server.repository.ExternalClusterRepository externalClusterRepository;
    private final ExternalClusterDiscoveryService externalClusterDiscoveryService;
    private final java.util.Map<String, ExternalAgentTask> pendingTasks = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, ExternalAgentTask> completedTasks = new java.util.concurrent.ConcurrentHashMap<>();


    public Map<String, String> getExternalTaskData(String taskId) {
        for (ExternalAgentTask task : pendingTasks.values()) {
            if (taskId.equals(task.getTaskId())) {
                return task.getData();
            }
        }
        return null;
    }


    public void removeExternalTask(String taskId) {
        pendingTasks.entrySet().removeIf(entry -> taskId.equals(entry.getValue().getTaskId()));
    }


    public Map<String, Object> queueRestart(UUID clusterId) {
        ExternalCluster cluster = externalClusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("External cluster not found."));

        List<ExternalBrokerRecord> brokers = externalClusterDiscoveryService.readBrokerRecords(cluster).stream()
                .filter(this::isAgentRecord)
                .toList();
        if (brokers.isEmpty()) {
            throw new IllegalArgumentException("No discovery agent is attached to this external cluster.");
        }

        String taskId = UUID.randomUUID().toString();
        for (ExternalBrokerRecord broker : brokers) {
            ExternalAgentTask task = new ExternalAgentTask();
            task.setTaskId(taskId);
            task.setTask("RESTART");
            task.setStatus("PENDING");
            task.setClusterName(cluster.getName());
            task.setHostname(broker.getHostname());
            task.setBootstrap(broker.getBootstrap());
            pendingTasks.put(ExternalClusterUtil.taskKey(cluster.getName(), broker.getHostname(), broker.getBootstrap()), task);
        }

        return Map.of("taskId", taskId, "status", "queued", "brokers", String.valueOf(brokers.size()));
    }


    public Map<String, Object> queueConfigUpdate(UUID clusterId, String configKey, String configValue, boolean restart) {
        ExternalCluster cluster = externalClusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("External cluster not found."));

        List<ExternalBrokerRecord> brokers = externalClusterDiscoveryService.readBrokerRecords(cluster).stream()
                .filter(this::isAgentRecord)
                .toList();
        if (brokers.isEmpty()) {
            throw new IllegalArgumentException("No discovery agent is attached to this external cluster.");
        }

        String taskId = UUID.randomUUID().toString();
        for (ExternalBrokerRecord broker : brokers) {
            ExternalAgentTask task = new ExternalAgentTask();
            task.setTaskId(taskId);
            task.setTask("UPDATE_CONFIG");
            task.setStatus("PENDING");
            task.setClusterName(cluster.getName());
            task.setHostname(broker.getHostname());
            task.setBootstrap(broker.getBootstrap());
            task.setConfigKey(configKey);
            task.setConfigValue(configValue);
            task.setRestart(restart);
            pendingTasks.put(ExternalClusterUtil.taskKey(cluster.getName(), broker.getHostname(), broker.getBootstrap()), task);
        }

        return Map.of("taskId", taskId, "status", "queued", "brokers", String.valueOf(brokers.size()));
    }


    public Map<String, Object> queueTask(UUID clusterId, String hostname, String taskName, Map<String, Object> payload) {
        ExternalCluster cluster = externalClusterRepository.findById(clusterId)
                .orElseThrow(() -> new RuntimeException("External cluster not found"));
        
        String taskId = UUID.randomUUID().toString();
        ExternalAgentTask task = new ExternalAgentTask();
        task.setTaskId(taskId);
        task.setTask(taskName);
        task.setClusterName(cluster.getName());
        task.setStatus("PENDING");
        task.setHostname(hostname);
        task.setBootstrap(cluster.getBootstrapServers());
        
        if (payload != null) {
            if (payload.containsKey("configFilePath")) task.setConfigFilePath(String.valueOf(payload.get("configFilePath")));
            if (payload.containsKey("backupDirPath")) task.setBackupDirPath(String.valueOf(payload.get("backupDirPath")));
            if (payload.containsKey("backupFilePath")) task.setBackupFilePath(String.valueOf(payload.get("backupFilePath")));
            if (payload.containsKey("serviceName")) task.setServiceName(String.valueOf(payload.get("serviceName")));
            if (payload.containsKey("configChanges")) task.setConfigChanges((Map<String, String>) payload.get("configChanges"));
        }
        
        String key = ExternalClusterUtil.taskKey(cluster.getName(), hostname, cluster.getBootstrapServers());
        pendingTasks.put(key, task);
        
        return Map.of("taskId", taskId, "status", "queued");
    }


    public Map<String, Object> pollAgentTask(String clusterName, String hostname, String bootstrap) {
        ExternalAgentTask task = pendingTasks.get(ExternalClusterUtil.taskKey(clusterName, hostname, bootstrap));
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return Map.of("task", "NONE");
        }
        task.setStatus("IN_PROGRESS");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("task", task.getTask());
        response.put("taskId", task.getTaskId());
        if (task.getConfigKey() != null) {
            response.put("configKey", task.getConfigKey());
        }
        if (task.getConfigValue() != null) {
            response.put("configValue", task.getConfigValue());
        }
        if (task.getConfigFilePath() != null) {
            response.put("configFilePath", task.getConfigFilePath());
        }
        if (task.getBackupDirPath() != null) {
            response.put("backupDirPath", task.getBackupDirPath());
        }
        if (task.getBackupFilePath() != null) {
            response.put("backupFilePath", task.getBackupFilePath());
        }
        if (task.getConfigChanges() != null) {
            response.put("configChanges", task.getConfigChanges());
        }
        if (task.getServiceName() != null) {
            response.put("serviceName", task.getServiceName());
        }
        response.put("restart", String.valueOf(task.isRestart()));
        return response;
    }


    public void completeAgentTask(String clusterName, String hostname, String bootstrap, AgentTaskCompletion completion) {
        ExternalAgentTask task = pendingTasks.get(ExternalClusterUtil.taskKey(clusterName, hostname, bootstrap));
        if (task == null) {
            return;
        }
        task.setStatus(ExternalClusterUtil.blankToDefault(completion.getStatus(), "SUCCESS"));
        task.setMessage(completion.getMessage());
        if (completion.getData() != null) {
            task.setData(completion.getData());
        }
        if (!"FAILED".equalsIgnoreCase(task.getStatus())) {
            completedTasks.put(task.getTaskId(), task);
            pendingTasks.remove(ExternalClusterUtil.taskKey(clusterName, hostname, bootstrap));
        } else {
            completedTasks.put(task.getTaskId(), task);
            pendingTasks.remove(ExternalClusterUtil.taskKey(clusterName, hostname, bootstrap));
        }
    }


    public Map<String, Object> getExternalTaskStatus(String taskId) {
        Map<String, Object> result = new HashMap<>();
        
        ExternalAgentTask completed = completedTasks.get(taskId);
        if (completed != null) {
            result.put("taskId", taskId);
            result.put("status", completed.getStatus());
            if (completed.getMessage() != null) {
                result.put("message", completed.getMessage());
            }
            if (completed.getData() != null) {
                result.put("data", completed.getData());
            }
            completedTasks.remove(taskId);
            return result;
        }

        boolean anyPending = false;
        boolean anyFailed = false;
        List<String> messages = new ArrayList<>();
        for (ExternalAgentTask task : pendingTasks.values()) {
            if (!ExternalClusterUtil.safeEquals(task.getTaskId(), taskId)) {
                continue;
            }
            if ("FAILED".equalsIgnoreCase(task.getStatus())) {
                anyFailed = true;
            }
            if (!"SUCCESS".equalsIgnoreCase(task.getStatus())) {
                anyPending = true;
            }
            if (task.getMessage() != null && !task.getMessage().isBlank()) {
                messages.add(task.getMessage());
            }
        }
        
        result.put("taskId", taskId);
        if (anyFailed) {
            result.put("status", "FAILED");
            result.put("message", String.join("; ", messages));
        } else if (anyPending) {
            result.put("status", "IN_PROGRESS");
        } else {
            result.put("status", "SUCCESS");
        }
        return result;
    }


    private boolean isAgentRecord(ExternalBrokerRecord record) {
        return record.getInstallPath() != null && !record.getInstallPath().isBlank();
    }

}
