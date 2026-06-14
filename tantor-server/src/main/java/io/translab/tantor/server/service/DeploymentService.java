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
                for (Map.Entry<String, Object> entry : configMap.entrySet()) {
                    if (entry.getValue() != null) {
                        params.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
            
            // Set default install dir if not provided
            if (!params.containsKey("kafka_install_dir")) {
                params.put("kafka_install_dir", "/opt/tantor/kafka");
            }

            // Inject JMX Exporter artifact URL so Agent can pull it securely
            if (artifactUrl != null && artifactUrl.contains("/api/v1/artifacts/")) {
                String baseUrl = artifactUrl.substring(0, artifactUrl.indexOf("/api/v1/artifacts/") + 18);
                String jmxUrl = baseUrl + "4d646b0b-5b61-4b3b-9ed4-8f8910516677/download";
                params.put("jmx_artifact_url", jmxUrl);
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

    @Transactional
    public void restartService(String hostId, String serviceName) {
        Task task = new Task();
        task.setHostId(hostId);
        task.setCommand("RESTART_SERVICE");
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

    @Transactional
    public void updateKafkaConfig(String hostId, String configJsonStr, boolean restart) {
        Task task = new Task();
        task.setHostId(hostId);
        task.setCommand("UPDATE_KAFKA_CONFIG");
        task.setStatus("PENDING");
        
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("restart", String.valueOf(restart));
            
            if (configJsonStr != null && !configJsonStr.equals("{}")) {
                Map<String, Object> configMap = objectMapper.readValue(configJsonStr, Map.class);
                for (Map.Entry<String, Object> entry : configMap.entrySet()) {
                    if (entry.getValue() != null) {
                        params.put(entry.getKey(), String.valueOf(entry.getValue()));
                    }
                }
            }
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameters", e);
        }

        taskRepository.save(task);
    }

    @Transactional
    public void deleteClusterFromHost(String hostId) {
        Task task = new Task();
        task.setHostId(hostId);
        task.setCommand("DELETE_CLUSTER");
        task.setStatus("PENDING");
        task.setParameters("{}");
        taskRepository.save(task);
        log.info("Dispatched DELETE_CLUSTER task for host {}", hostId);
    }
}
