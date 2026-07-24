package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.web.ConfigController.BulkConfigRequest;
import io.translab.tantor.server.web.ConfigController.ServiceConfigUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.repository.*;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ConfigMutationService {
    private final ClusterRepository clusterRepository;
    private final JobService jobService;
    private final ActivityAlertService activityAlertService;
    private final KafkaAdminService kafkaAdminService;
    private final ObjectMapper objectMapper;

    @Transactional
        @PostMapping("/rolling-apply")
        public ResponseEntity<Map<String, Object>> rollingApply(
                @RequestHeader(value = "Authorization", required = false) String authorization,
                @PathVariable UUID clusterId,
                @RequestBody Map<String, Object> payload) {
            Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
            if (cluster == null) return ResponseEntity.notFound().build();
    
            Job job = new Job();
            job.setType(JobType.ROLLING_CONFIG_UPDATE);
            job.setStatus(JobStatus.PENDING);
            job.setResourceKey("cluster:" + clusterId);
            job.setRollbackSupported(false);
    
            try {
                Map<String, Object> jobPayload = new HashMap<>();
                jobPayload.put("clusterId", clusterId.toString());
                jobPayload.put("rollingRestart", payload.get("rollingRestart"));
                jobPayload.put("changes", payload.get("changes"));
                job.setPayload(objectMapper.writeValueAsString(jobPayload));
            } catch (JsonProcessingException e) {
                return ResponseEntity.badRequest().build();
            }
    
            List<JobStep> steps = new ArrayList<>();
            int order = 1;
            steps.add(createStep("ALL", "PREFLIGHT", order++));
            steps.add(createStep("ALL", "BACKUP_ALL", order++));
    
            List<Map<String, Object>> changes = (List<Map<String, Object>>) payload.get("changes");
            if (changes != null) {
                for (Map<String, Object> change : changes) {
                    String host = (String) change.get("host");
                    steps.add(createStep(host, "WRITE_CONFIG: " + host, order++));
                    if (Boolean.TRUE.equals(payload.get("rollingRestart"))) {
                        steps.add(createStep(host, "RESTART_SERVICE: " + host, order++));
                        steps.add(createStep(host, "HEALTH_CHECK: " + host, order++));
                    }
                }
            }
            steps.add(createStep("ALL", "FINAL_VERIFY", order++));
    
            Job savedJob = jobService.createJob(job, steps);
            
            activityAlertService.logActivity("INFO", "Requested rolling config update for cluster: " + cluster.getName(), clusterId);
            
            return ResponseEntity.ok(Map.of("jobId", savedJob.getId().toString()));
        }

    @Transactional
        @Deprecated
        @PutMapping("/unsafe-legacy/services/{serviceId}")
        public ResponseEntity<?> updateServiceConfig(
                @RequestHeader(value = "Authorization", required = false) String authorization,
                @PathVariable UUID clusterId,
                @PathVariable UUID serviceId,
                @RequestBody ServiceConfigUpdateRequest request
        ) throws JsonProcessingException {
            Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
            if (cluster == null) return ResponseEntity.notFound().build();
            ClusterServiceAssignment service = cluster.getServices() == null ? null : cluster.getServices().stream()
                    .filter(item -> serviceId.equals(item.getId()))
                    .findFirst()
                    .orElse(null);
            if (service == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Service assignment does not belong to this cluster."));
            }
            if (request.getProperties() == null || request.getProperties().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("message", "At least one configuration property is required."));
            }
            for (String key : request.getProperties().keySet()) {
                if (key == null || !key.matches("[A-Za-z0-9._-]+")) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Invalid configuration key: " + key));
                }
            }
    
            Map<String, Object> deploymentConfig = new HashMap<>();
            if (cluster.getConfigJson() != null && !cluster.getConfigJson().isBlank()) {
                Map<String, Object> parsed = objectMapper.readValue(cluster.getConfigJson(), Map.class);
                if (parsed != null) deploymentConfig.putAll(parsed);
            }
            deploymentConfig.putAll(ConfigUtil.serviceConfig(deploymentConfig, service, objectMapper));
            deploymentConfig.put("mode", cluster.getMode());
            deploymentConfig.put("version", cluster.getKafkaVersion());
    
            String previousServiceConfigJson = service.getConfigJson() == null ? "{}" : service.getConfigJson();
            String previousPropertiesTemplate = "";
            try {
                Map<String, Object> previousStored = objectMapper.readValue(previousServiceConfigJson, Map.class);
                Object previousProperties = previousStored.get("properties");
                if (previousProperties instanceof Map<?, ?> propertyMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    propertyMap.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    previousPropertiesTemplate = ConfigUtil.serializeProperties(normalized);
                }
            } catch (Exception ignored) {
                // An empty rollback template is safer than rejecting a valid forward change.
            }
    
            Map<String, Object> stored = new HashMap<>(deploymentConfig);
            stored.put("properties", new LinkedHashMap<>(request.getProperties()));
            service.setConfigJson(objectMapper.writeValueAsString(stored));
            clusterRepository.save(cluster);
    
            String propertiesTemplate = ConfigUtil.serializeProperties(request.getProperties());
            Map<String, Object> stepPayload = new LinkedHashMap<>();
            stepPayload.put("operation", "service");
            stepPayload.put("hostId", service.getHostId());
            stepPayload.put("role", service.getRole());
            stepPayload.put("nodeId", service.getNodeId() == null ? "1" : String.valueOf(service.getNodeId()));
            stepPayload.put("configJson", objectMapper.writeValueAsString(deploymentConfig));
            stepPayload.put("propertiesTemplate", propertiesTemplate);
            stepPayload.put("previousConfigJson", previousServiceConfigJson);
            stepPayload.put("previousPropertiesTemplate", previousPropertiesTemplate);
            stepPayload.put("restart", request.isRestart());
    
            Job job = new Job();
            job.setType(JobType.CONFIG_CHANGE);
            job.setStatus(JobStatus.PENDING);
            job.setRollbackSupported(true);
            job.setResourceKey("cluster:" + clusterId);
            job.setPayload(objectMapper.writeValueAsString(Map.of("clusterId", clusterId.toString())));
            JobStep step = new JobStep();
            step.setStepOrder(1);
            step.setTargetId(service.getHostId());
            step.setName("Update " + ConfigUtil.serviceConfigPath(service.getRole(), cluster.getMode(), cluster.getKafkaVersion(), ConfigUtil.activeKafkaInstallDir(deploymentConfig))
                    + " on " + service.getHostId());
            step.setPayload(objectMapper.writeValueAsString(stepPayload));
            Job savedJob = jobService.createJob(job, List.of(step));
            activityAlertService.logAudit("INFO", "CONFIGURATION", "UPDATE",
                    "Updated " + ConfigUtil.serviceConfigPath(service.getRole(), cluster.getMode(), cluster.getKafkaVersion(), ConfigUtil.activeKafkaInstallDir(deploymentConfig))
                            + " on " + service.getHostId() + (request.isRestart() ? " and queued service restart" : ""),
                    "SERVICE", serviceId.toString(), clusterId,
                    ConfigUtil.auditProperties(previousPropertiesTemplate), ConfigUtil.auditProperties(propertiesTemplate), "QUEUED", null,
                    "jobId=" + savedJob.getId());
            return ResponseEntity.ok(Map.of("jobId", savedJob.getId().toString(), "status", "scheduled"));
        }

    @Transactional
        @PutMapping("/bulk")
        public ResponseEntity<?> updateConfigBulk(
                @RequestHeader(value = "Authorization", required = false) String authorization,
                @PathVariable UUID clusterId,
                @RequestBody BulkConfigRequest request) throws JsonProcessingException {
            Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
            if (cluster == null) return ResponseEntity.notFound().build();
            if (request.getConfigKey() == null || !request.getConfigKey().matches("[A-Za-z0-9._-]+")) {
                return ResponseEntity.badRequest().body(Map.of("message", "A valid configuration key is required."));
            }
    
            List<JobStep> steps = new ArrayList<>();
            Map<Integer, Map<String, Object>> currentConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
            Map<String, Object> previousByBroker = new LinkedHashMap<>();
            currentConfigs.forEach((brokerId, config) -> previousByBroker.put(
                    String.valueOf(brokerId), String.valueOf(config.getOrDefault(request.getConfigKey(), ""))));
    
            JobStep dynamicStep = new JobStep();
            dynamicStep.setStepOrder(1);
            dynamicStep.setName("Apply dynamic broker configuration " + request.getConfigKey());
            dynamicStep.setTargetId(clusterId.toString());
            dynamicStep.setPayload(objectMapper.writeValueAsString(Map.of(
                    "operation", "dynamic",
                    "configKey", request.getConfigKey(),
                    "configValue", request.getConfigValue() == null ? "" : request.getConfigValue(),
                    "previousByBroker", previousByBroker
            )));
            steps.add(dynamicStep);
    
            if (request.isApplyToAgents() && "EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
                JobStep externalStep = new JobStep();
                externalStep.setStepOrder(2);
                externalStep.setName("Persist configuration through discovery agents");
                externalStep.setTargetId(clusterId.toString());
                externalStep.setPayload(objectMapper.writeValueAsString(Map.of(
                        "operation", "external",
                        "configKey", request.getConfigKey(),
                        "configValue", request.getConfigValue() == null ? "" : request.getConfigValue(),
                        "previousValue", previousByBroker.values().stream().findFirst().orElse(""),
                        "restart", request.isRestart()
                )));
                steps.add(externalStep);
            }
    
            if (request.isApplyToAgents() && !"EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
                Map<String, Object> oldConfig = cluster.getConfigJson() == null || cluster.getConfigJson().isBlank()
                        ? new HashMap<>() : objectMapper.readValue(cluster.getConfigJson(), Map.class);
                Map<String, Object> newConfig = new HashMap<>(oldConfig);
                newConfig.put(request.getConfigKey(), request.getConfigValue());
                String oldConfigJson = objectMapper.writeValueAsString(oldConfig);
                String newConfigJson = objectMapper.writeValueAsString(newConfig);
                int order = 2;
                for (ClusterServiceAssignment service : cluster.getServices()) {
                    if (!List.of("broker", "broker_controller", "broker_zookeeper").contains(service.getRole())) continue;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("operation", "service");
                    payload.put("hostId", service.getHostId());
                    payload.put("role", service.getRole());
                    payload.put("nodeId", service.getNodeId() == null ? "1" : String.valueOf(service.getNodeId()));
                    payload.put("configJson", newConfigJson);
                    payload.put("clusterConfigJson", newConfigJson);
                    payload.put("propertiesTemplate", request.getConfigKey() + "=" + request.getConfigValue() + "\n");
                    payload.put("previousConfigJson", oldConfigJson);
                    payload.put("previousPropertiesTemplate", request.getConfigKey() + "=" + previousByBroker.getOrDefault(String.valueOf(service.getNodeId()), "") + "\n");
                    payload.put("restart", request.isRestart());
                    JobStep step = new JobStep();
                    step.setStepOrder(order++);
                    step.setName("Persist configuration on " + service.getHostId());
                    step.setTargetId(service.getHostId());
                    step.setPayload(objectMapper.writeValueAsString(payload));
                    steps.add(step);
                }
            }
    
            Job job = new Job();
            job.setType(JobType.CONFIG_CHANGE);
            job.setStatus(JobStatus.PENDING);
            job.setRollbackSupported(true);
            job.setResourceKey("cluster:" + clusterId);
            job.setPayload(objectMapper.writeValueAsString(Map.of("clusterId", clusterId.toString())));
            Job saved = jobService.createJob(job, steps);
            activityAlertService.logAudit("INFO", "CONFIGURATION", "UPDATE",
                    "Created configuration change job for " + request.getConfigKey(), "CLUSTER", clusterId.toString(), clusterId,
                    ConfigUtil.auditConfigValue(request.getConfigKey(), previousByBroker.toString()),
                    ConfigUtil.auditConfigValue(request.getConfigKey(), request.getConfigValue()), "QUEUED", null,
                    "jobId=" + saved.getId() + ";restart=" + request.isRestart());
            return ResponseEntity.ok(Map.of("jobId", saved.getId().toString(), "status", saved.getStatus().name()));
        }

    public JobStep createStep(String targetId, String name, int order) {
            JobStep step = new JobStep();
            step.setTargetId(targetId);
            step.setName(name);
            step.setStepOrder(order);
            step.setStatus(JobStepStatus.PENDING);
            return step;
        }

}
