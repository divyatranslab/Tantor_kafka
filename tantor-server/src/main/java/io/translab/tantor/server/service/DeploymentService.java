package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.repository.HostParcelRepository;
import io.translab.tantor.server.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeploymentService {

    private final TaskRepository taskRepository;
    private final HostParcelRepository hostParcelRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void deployKafkaToHost(UUID clusterId, String hostId, String version, String artifactUrl, String checksum, String nodeId, String quorumVoters, String role, String configJsonStr) {
        log.info("Scheduling Kafka {} deployment on host {}", version, hostId);

        Task task = createTask(clusterId, hostId, "INSTALL_KAFKA");
        task.setArtifactUrl(artifactUrl);
        task.setChecksum(checksum);
        
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("version", version);
            params.put("node_id", nodeId != null ? nodeId : "1");
            params.put("quorum_voters", quorumVoters != null ? quorumVoters : "1@localhost:9093");
            String normalizedRole = role != null && !role.isBlank() ? role : "broker_controller";
            params.put("role", normalizedRole);
            params.put("service_role", normalizedRole);
            params.put("service_name", systemdServiceName(normalizedRole));
            params.put("systemd_service", systemdServiceName(normalizedRole));
            params.put("config_file", configFileForRole(normalizedRole));
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }

            mergeConfigParams(params, configJsonStr);
            
            applyDefaultKafkaPaths(params);
            applyActiveParcelParams(params, hostId, version);

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
    public void upgradeKafkaOnHost(UUID clusterId, String hostId, String currentVersion, String targetVersion, String nodeId, String role, String configJsonStr) {
        log.info("Scheduling Kafka upgrade on host {} from {} to {}", hostId, currentVersion, targetVersion);

        Task task = createTask(clusterId, hostId, "UPGRADE_KAFKA");

        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("version", targetVersion);
            params.put("target_version", targetVersion);
            params.put("previous_version", currentVersion);
            params.put("node_id", nodeId != null ? nodeId : "1");
            String normalizedRole = role != null && !role.isBlank() ? role : "broker_controller";
            params.put("role", normalizedRole);
            params.put("service_role", normalizedRole);
            params.put("service_name", systemdServiceName(normalizedRole));
            params.put("systemd_service", systemdServiceName(normalizedRole));
            params.put("config_file", configFileForRole(normalizedRole));
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }

            mergeConfigParams(params, configJsonStr);
            applyDefaultKafkaPaths(params);
            if (!applyActiveParcelParams(params, hostId, targetVersion)) {
                throw new IllegalStateException("Kafka " + targetVersion + " is not active on host " + hostId + ".");
            }

            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize upgrade parameters", e);
            task.setParameters("{}");
        }

        taskRepository.save(task);
        log.info("Upgrade task {} created successfully", task.getId());
    }

    @Transactional
    public void startService(UUID clusterId, String hostId, String serviceName) {
        Task task = createTask(clusterId, hostId, "START_SERVICE");
        
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
    public void restartService(UUID clusterId, String hostId, String serviceName) {
        Task task = createTask(clusterId, hostId, "RESTART_SERVICE");
        
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
    public void updateKafkaConfig(UUID clusterId, String hostId, String configJsonStr, boolean restart) {
        Task task = createTask(clusterId, hostId, "UPDATE_KAFKA_CONFIG");
        
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("restart", String.valueOf(restart));
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }

            mergeConfigParams(params, configJsonStr);
            applyDefaultKafkaPaths(params);
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameters", e);
        }

        taskRepository.save(task);
    }

    @Transactional
    public void deleteClusterFromHost(UUID clusterId, String hostId, String version, String configJsonStr) {
        Task task = createTask(clusterId, hostId, "DELETE_CLUSTER");
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            if (version != null && !version.isBlank()) {
                params.put("version", version);
            }
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }
            mergeConfigParams(params, configJsonStr);
            applyDefaultKafkaPaths(params);
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize cleanup parameters", e);
            task.setParameters("{}");
        }
        taskRepository.save(task);
        log.info("Dispatched DELETE_CLUSTER task for host {} in cluster {}", hostId, clusterId);
    }


    private String systemdServiceName(String role) {
        if ("controller".equals(role)) return "controller";
        if ("zookeeper".equals(role)) return "zookeeper";
        if ("broker_controller".equals(role) || "broker_zookeeper".equals(role)) return "kafka";
        return "broker";
    }

    private String configFileForRole(String role) {
        if ("controller".equals(role)) return "controller.properties";
        if ("zookeeper".equals(role)) return "zookeeper.properties";
        if ("broker_controller".equals(role)) return "server.properties";
        return "broker.properties";
    }
    private Task createTask(UUID clusterId, String hostId, String command) {
        Task task = new Task();
        task.setClusterId(clusterId);
        task.setHostId(hostId);
        task.setCommand(command);
        task.setStatus("PENDING");
        return task;
    }

    @SuppressWarnings("unchecked")
    private void mergeConfigParams(Map<String, Object> params, String configJsonStr) throws JsonProcessingException {
        if (configJsonStr == null || configJsonStr.isBlank() || "{}".equals(configJsonStr)) {
            return;
        }

        Map<String, Object> configMap = objectMapper.readValue(configJsonStr, Map.class);
        for (Map.Entry<String, Object> entry : configMap.entrySet()) {
            if (entry.getValue() != null) {
                params.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
    }

    private void applyDefaultKafkaPaths(Map<String, Object> params) {
        Object installDir = params.get("kafka_install_dir");
        if (installDir == null || String.valueOf(installDir).isBlank()) {
            params.put("kafka_install_dir", "/opt");
        }
    }

    private boolean applyActiveParcelParams(Map<String, Object> params, String hostId, String version) {
        var activeParcel = hostParcelRepository.findByHostIdAndServiceTypeAndActiveTrue(hostId, "KAFKA").stream()
                .filter(parcel -> version != null && version.equals(parcel.getVersion()))
                .findFirst();
        activeParcel.ifPresent(parcel -> {
            params.put("use_active_parcel", "true");
            params.put("parcel_dir", parcel.getParcelDir());
        });
        return activeParcel.isPresent();
    }
}
