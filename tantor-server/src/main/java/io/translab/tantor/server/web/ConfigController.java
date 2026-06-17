package io.translab.tantor.server.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.DeploymentService;
import io.translab.tantor.server.service.KafkaAdminService;
import io.translab.tantor.server.service.ActivityAlertService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ClusterRepository clusterRepository;
    private final DeploymentService deploymentService;
    private final KafkaAdminService kafkaAdminService;
    private final ObjectMapper objectMapper;
    private final ActivityAlertService activityAlertService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getBrokerConfigs(@PathVariable UUID clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();

        Map<Integer, Map<String, Object>> dynamicConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
        
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("dynamicConfigs", dynamicConfigs);
        
        Map<String, Object> staticConfigs = new java.util.HashMap<>();
        String installDir = "/data/apps/kafka/install";
        
        try {
            if (cluster.getConfigJson() != null && !cluster.getConfigJson().isEmpty()) {
                Map<String, Object> parsedConfig = objectMapper.readValue(cluster.getConfigJson(), Map.class);
                staticConfigs.put("properties", parsedConfig);
                if (parsedConfig.containsKey("kafka_install_dir")) {
                    installDir = (String) parsedConfig.get("kafka_install_dir");
                }
            } else {
                staticConfigs.put("properties", new java.util.HashMap<>());
            }
        } catch(Exception e) {
            staticConfigs.put("properties", new java.util.HashMap<>());
        }
        
        staticConfigs.put("filePath", "zookeeper".equalsIgnoreCase(cluster.getMode())
                ? installDir + "/config/server.properties"
                : installDir + "/config/kraft/server.properties");
        response.put("staticConfigs", staticConfigs);

        return ResponseEntity.ok(response);
    }

    @PutMapping("/bulk")
    public ResponseEntity<?> updateConfigBulk(@PathVariable UUID clusterId, @RequestBody BulkConfigRequest request) throws JsonProcessingException {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();

        // 1. Update live dynamically via AdminClient
        Map<Integer, Map<String, Object>> currentConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
        boolean dynamicSuccess = true;
        String dynamicError = null;
        for (Integer brokerId : currentConfigs.keySet()) {
            try {
                kafkaAdminService.alterBrokerConfig(clusterId, brokerId, request.getConfigKey(), request.getConfigValue());
            } catch (Exception e) {
                dynamicSuccess = false;
                dynamicError = e.getMessage();
                // If not applying to agents, we must fail immediately
                if (!request.isApplyToAgents()) {
                    return ResponseEntity.badRequest().body(Map.of("message", "Failed to alter broker config dynamically: " + e.getMessage()));
                }
            }
        }

        // 2. Optionally push to static file and restart via Agent
        if (request.isApplyToAgents()) {
            // Need to update the DB blob so the agents get the new properties
            Map<String, Object> dbConfig = new java.util.HashMap<>();
            if (cluster.getConfigJson() != null && !cluster.getConfigJson().isEmpty()) {
                Map<String, Object> existing = objectMapper.readValue(cluster.getConfigJson(), Map.class);
                if (existing != null) {
                    dbConfig.putAll(existing);
                }
            }
            dbConfig.put(request.getConfigKey(), request.getConfigValue());
            String newConfigStr = objectMapper.writeValueAsString(dbConfig);
            cluster.setConfigJson(newConfigStr);
            clusterRepository.save(cluster);

            for (ClusterServiceAssignment svc : cluster.getServices()) {
                if ("broker".equals(svc.getRole()) || "broker_controller".equals(svc.getRole()) || "broker_zookeeper".equals(svc.getRole())) {
                    deploymentService.updateKafkaConfig(clusterId, svc.getHostId(), newConfigStr, request.isRestart());
                }
            }
            activityAlertService.logActivity(
                "INFO",
                "Updated server.properties config: " + request.getConfigKey() + " = " + request.getConfigValue() + (request.isRestart() ? " (Restarting brokers)" : ""),
                clusterId
            );
        } else if (dynamicSuccess) {
            activityAlertService.logActivity(
                "INFO",
                "Dynamically updated broker config: " + request.getConfigKey() + " = " + request.getConfigValue(),
                clusterId
            );
        }

        return ResponseEntity.ok().build();
    }

    @Data
    public static class BulkConfigRequest {
        private String configKey;
        private String configValue;
        private boolean applyToAgents = false;
        private boolean restart = false;
    }
}
