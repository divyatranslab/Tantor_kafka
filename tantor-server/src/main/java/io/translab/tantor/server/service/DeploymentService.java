package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentService {

    private final TaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void deployKafkaToHost(String hostId, String version, String artifactUrl, String checksum, String nodeId, String quorumVoters, String role, String configJsonStr) {
        log.info("Scheduling Kafka {} deployment on host {}", version, hostId);

        Task task = new Task();
        task.setHostId(hostId);
        task.setCommand("INSTALL_KAFKA");
        task.setArtifactUrl(artifactUrl);
        task.setChecksum(checksum);
        task.setStatus("PENDING");
        
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("version", version);
            params.put("node_id", nodeId != null ? nodeId : "1");
            params.put("quorum_voters", quorumVoters != null ? quorumVoters : "1@localhost:9093");
            params.put("role", role != null ? role : "broker_controller");

            // Merge advanced config into the task parameters
            if (configJsonStr != null && !configJsonStr.equals("{}")) {
                Map<String, Object> configMap = objectMapper.readValue(configJsonStr, Map.class);
                params.putAll(configMap);
            }
            
            // Set default install dir if not provided
            if (!params.containsKey("kafka_install_dir")) {
                params.put("kafka_install_dir", "/opt/tantor/kafka");
            }

            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameters", e);
        }

        taskRepository.save(task);
        log.info("Task {} created successfully", task.getId());
    }

    @Transactional
    public void startService(String hostId, String serviceName) {
        Task task = new Task();
        task.setHostId(hostId);
        task.setCommand("START_SERVICE");
        task.setStatus("PENDING");
        
        try {
            task.setParameters(objectMapper.writeValueAsString(Map.of(
                "service_name", serviceName
            )));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameters", e);
        }

        taskRepository.save(task);
    }
}
