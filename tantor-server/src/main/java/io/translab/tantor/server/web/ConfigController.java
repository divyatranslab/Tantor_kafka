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
    public ResponseEntity<Map<Integer, Map<String, String>>> getBrokerConfigs(@PathVariable UUID clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();

        Map<Integer, Map<String, String>> configs = kafkaAdminService.getBrokerConfigs(clusterId);
        return ResponseEntity.ok(configs);
    }

    @PutMapping("/bulk")
    public ResponseEntity<?> updateConfigBulk(@PathVariable UUID clusterId, @RequestBody BulkConfigRequest request) throws JsonProcessingException {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();

        // 1. Update live dynamically via AdminClient
        Map<Integer, Map<String, String>> currentConfigs = kafkaAdminService.getBrokerConfigs(clusterId);
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
            Map<String, Object> dbConfig = Map.of();
            if (cluster.getConfigJson() != null && !cluster.getConfigJson().isEmpty()) {
                dbConfig = objectMapper.readValue(cluster.getConfigJson(), Map.class);
            }
            dbConfig.put(request.getConfigKey(), request.getConfigValue());
            String newConfigStr = objectMapper.writeValueAsString(dbConfig);
            cluster.setConfigJson(newConfigStr);
            clusterRepository.save(cluster);

            for (ClusterServiceAssignment svc : cluster.getServices()) {
                if ("broker".equals(svc.getRole()) || "broker_controller".equals(svc.getRole())) {
                    deploymentService.updateKafkaConfig(svc.getHostId(), newConfigStr, request.isRestart());
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
