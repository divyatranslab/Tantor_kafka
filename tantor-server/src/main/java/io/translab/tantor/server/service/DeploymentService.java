package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Task;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
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
    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UUID deployKafkaToHost(UUID clusterId, String hostId, String version, String artifactUrl, String checksum, String nodeId, String quorumVoters, String role, String configJsonStr) {
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
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }
            addHostIdentity(params, hostId);

            mergeConfigParams(params, configJsonStr);
            params.put("config_file", configFileForRole(normalizedRole, String.valueOf(params.getOrDefault("mode", "kraft"))));
            
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
        return task.getId();
    }

    private void addHostIdentity(Map<String, Object> params, String hostId) {
        params.put("host_id", hostId);
        hostRepository.findById(hostId).ifPresent(host -> {
            params.put("host_hostname", host.getHostname() == null ? "" : host.getHostname());
            params.put("host_ip", firstHostIp(host.getIpAddresses()));
        });
    }

    private String firstHostIp(String ipAddresses) {
        if (ipAddresses == null || ipAddresses.isBlank() || "[]".equals(ipAddresses)) return "";
        try {
            java.util.List<?> values = objectMapper.readValue(ipAddresses, java.util.List.class);
            return values.isEmpty() ? "" : String.valueOf(values.get(0));
        } catch (Exception ignored) {
            return ipAddresses.replaceAll("\\[|\\]|\\\"", "").split(",")[0].trim();
        }
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
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }

            mergeConfigParams(params, configJsonStr);
            params.put("config_file", configFileForRole(normalizedRole, String.valueOf(params.getOrDefault("mode", "kraft"))));
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
    public UUID restartService(UUID clusterId, String hostId, String serviceName) {
        Task task = createTask(clusterId, hostId, "RESTART_SERVICE");
        
        try {
            task.setParameters(objectMapper.writeValueAsString(Map.of(
                "service_name", serviceName
            )));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameters", e);
        }

        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public void updateKafkaConfig(UUID clusterId, String hostId, String configJsonStr, boolean restart) {
        updateKafkaConfig(clusterId, hostId, "broker", "1", configJsonStr, "", restart, "unversioned", null);
    }

    @Transactional
    public UUID updateKafkaConfig(
            UUID clusterId,
            String hostId,
            String role,
            String nodeId,
            String configJsonStr,
            String propertiesTemplate,
            boolean restart,
            String configVersion,
            String configPath
    ) {
        Task task = createTask(clusterId, hostId, "UPDATE_KAFKA_CONFIG");
        
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("restart", String.valueOf(restart));
            String normalizedRole = role == null || role.isBlank() ? "broker" : role;
            params.put("role", normalizedRole);
            params.put("service_role", normalizedRole);
            params.put("node_id", nodeId == null || nodeId.isBlank() ? "1" : nodeId);
            params.put("service_name", systemdServiceName(normalizedRole));
            params.put("systemd_service", systemdServiceName(normalizedRole));
            params.put("config_version", configVersion == null || configVersion.isBlank() ? "unversioned" : configVersion);
            if (clusterId != null) {
                params.put("cluster_id", clusterId.toString());
            }

            mergeConfigParams(params, configJsonStr);
            params.put("config_file", configFileForRole(normalizedRole, String.valueOf(params.getOrDefault("mode", "kraft"))));
            if (configPath != null && !configPath.isBlank()) {
                params.put("config_path", configPath.trim());
            }
            if (propertiesTemplate != null && !propertiesTemplate.isBlank()) {
                if ("controller".equals(normalizedRole)) {
                    params.put("controller_properties_template", propertiesTemplate);
                } else if ("zookeeper".equals(normalizedRole)) {
                    params.put("zookeeper_properties_template", propertiesTemplate);
                } else if ("broker_controller".equals(normalizedRole)) {
                    params.put("server_properties_template", propertiesTemplate);
                } else {
                    params.put("broker_properties_template", propertiesTemplate);
                }
            }
            applyDefaultKafkaPaths(params);
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize parameters", e);
        }

        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public UUID deleteClusterFromHost(UUID clusterId, String hostId, String version, String configJsonStr) {
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
        return task.getId();
    }

    @Transactional
    public UUID installMonitoring(
            UUID clusterId,
            String hostId,
            String installDir,
            String prometheusUrl,
            String grafanaUrl
    ) {
        Task task = createTask(clusterId, hostId, "INSTALL_MONITORING");
        try {
            task.setParameters(objectMapper.writeValueAsString(Map.of(
                    "install_dir", installDir == null || installDir.isBlank() ? "/opt/tantor/monitoring" : installDir,
                    "prometheus_url", prometheusUrl == null ? "" : prometheusUrl,
                    "grafana_url", grafanaUrl == null ? "" : grafanaUrl
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid monitoring deployment parameters", e);
        }
        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public UUID removeMonitoring(UUID clusterId, String hostId, String installDir) {
        Task task = createTask(clusterId, hostId, "DELETE_MONITORING");
        try {
            task.setParameters(objectMapper.writeValueAsString(Map.of(
                    "install_dir", installDir == null || installDir.isBlank() ? "/opt/tantor/monitoring" : installDir
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid monitoring rollback parameters", e);
        }
        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public UUID checkKRaftConnectivity(UUID clusterId, String hostId, String controllerEndpoints) {
        Task task = createTask(clusterId, hostId, "CHECK_KRAFT_CONNECTIVITY");
        try {
            task.setParameters(objectMapper.writeValueAsString(Map.of(
                    "controller_endpoints", controllerEndpoints == null ? "" : controllerEndpoints
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid KRaft connectivity parameters", e);
        }
        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public UUID verifyKRaftQuorum(
            UUID clusterId,
            String hostId,
            String controllerEndpoints,
            String clusterUuid,
            String expectedControllerCount,
            String configJsonStr
    ) {
        Task task = createTask(clusterId, hostId, "VERIFY_KRAFT_QUORUM");
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("controller_endpoints", controllerEndpoints == null ? "" : controllerEndpoints);
            params.put("cluster_uuid", clusterUuid == null ? "" : clusterUuid);
            params.put("expected_controller_count", expectedControllerCount == null ? "" : expectedControllerCount);
            mergeConfigParams(params, configJsonStr);
            applyDefaultKafkaPaths(params);
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid KRaft quorum verification parameters", e);
        }
        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public UUID verifyZooKeeperQuorum(
            UUID clusterId,
            String hostId,
            String zookeeperConnect,
            String configJsonStr
    ) {
        Task task = createTask(clusterId, hostId, "VERIFY_ZK_QUORUM");
        try {
            Map<String, Object> params = new java.util.HashMap<>();
            params.put("zookeeper_connect", zookeeperConnect == null ? "" : zookeeperConnect);
            mergeConfigParams(params, configJsonStr);
            applyDefaultKafkaPaths(params);
            task.setParameters(objectMapper.writeValueAsString(params));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid ZooKeeper quorum verification parameters", e);
        }
        taskRepository.save(task);
        return task.getId();
    }

    @Transactional
    public boolean retryTask(UUID taskId) {
        return taskRepository.findById(taskId).map(task -> {
            if ("FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
                task.setStatus("PENDING");
                task.setErrorMsg(null);
                task.setFailedReason(null);
                task.setLogOutput(null);
                task.setStepLogs(null);
                task.setCurrentStep(null);
                taskRepository.save(task);
                
                if (task.getClusterId() != null) {
                    clusterRepository.findById(task.getClusterId()).ifPresent(c -> {
                        c.setStatus("RUNNING");
                        clusterRepository.save(c);
                    });
                }
                
                return true;
            }
            return false;
        }).orElse(false);
    }

    @Transactional
    public boolean resumeTask(UUID taskId) {
        return taskRepository.findById(taskId).map(task -> {
            if ("FAILED".equals(task.getStatus()) || "CANCELLED".equals(task.getStatus())) {
                task.setStatus("PENDING");
                task.setErrorMsg(null);
                task.setFailedReason(null);
                try {
                    Map<String, Object> params = objectMapper.readValue(task.getParameters(), Map.class);
                    if (task.getCurrentStep() != null) {
                        params.put("resume_step", task.getCurrentStep());
                    }
                    task.setParameters(objectMapper.writeValueAsString(params));
                } catch (Exception e) {
                    log.error("Failed to inject resume_step into parameters", e);
                }
                taskRepository.save(task);
                
                if (task.getClusterId() != null) {
                    clusterRepository.findById(task.getClusterId()).ifPresent(c -> {
                        c.setStatus("RUNNING");
                        clusterRepository.save(c);
                    });
                }
                return true;
            }
            return false;
        }).orElse(false);
    }

    @Transactional
    public boolean rollbackTask(UUID clusterId, UUID taskId) {
        return taskRepository.findById(taskId).map(task -> {
            if ("FAILED".equals(task.getStatus())) {
                task.setStatus("ROLLBACK_PENDING");
                taskRepository.save(task);
                
                Task rollbackTask = createTask(clusterId, task.getHostId(), "ROLLBACK_DEPLOYMENT");
                try {
                    Map<String, Object> params = objectMapper.readValue(task.getParameters(), Map.class);
                    params.put("original_task_id", task.getId().toString());
                    rollbackTask.setParameters(objectMapper.writeValueAsString(params));
                } catch (Exception e) {
                    log.error("Failed to serialize parameters for rollback", e);
                    rollbackTask.setParameters("{\"original_task_id\":\"" + task.getId().toString() + "\"}");
                }
                taskRepository.save(rollbackTask);
                return true;
            }
            return false;
        }).orElse(false);
    }

    @Transactional
    public boolean cleanupTask(UUID clusterId, UUID taskId) {
        return taskRepository.findById(taskId).map(task -> {
            if ("FAILED".equals(task.getStatus()) || "ROLLBACK_DONE".equals(task.getStatus())) {
                task.setStatus("CLEANUP_PENDING");
                taskRepository.save(task);
                
                Task cleanupTask = createTask(clusterId, task.getHostId(), "DELETE_CLUSTER");
                try {
                    Map<String, Object> params = objectMapper.readValue(task.getParameters(), Map.class);
                    params.put("original_task_id", task.getId().toString());
                    cleanupTask.setParameters(objectMapper.writeValueAsString(params));
                } catch (Exception e) {
                    cleanupTask.setParameters("{\"original_task_id\":\"" + task.getId().toString() + "\"}");
                }
                taskRepository.save(cleanupTask);
                return true;
            }
            return false;
        }).orElse(false);
    }



    private String systemdServiceName(String role) {
        if ("controller".equals(role)) return "controller";
        if ("zookeeper".equals(role)) return "zookeeper";
        if ("broker_controller".equals(role) || "broker_zookeeper".equals(role)) return "kafka";
        return "broker";
    }

    private String configFileForRole(String role, String mode) {
        if ("zookeeper".equalsIgnoreCase(mode)) {
            return "zookeeper".equals(role) ? "zookeeper.properties" : "server.properties";
        }
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
        var activeParcel = hostParcelRepository.findLatestActive(hostId, "KAFKA").stream()
                .filter(parcel -> version != null && version.equals(parcel.getVersion()))
                .findFirst();
        activeParcel.ifPresent(parcel -> {
            params.put("use_active_parcel", "true");
            params.put("parcel_dir", parcel.getParcelDir());
        });
        return activeParcel.isPresent();
    }
}
