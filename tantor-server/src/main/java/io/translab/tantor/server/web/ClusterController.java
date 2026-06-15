package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.DeploymentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ui/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final DeploymentService deploymentService;
    private final ClusterRepository clusterRepository;
    private final io.translab.tantor.server.repository.TaskRepository taskRepository;
    private final io.translab.tantor.server.repository.HostRepository hostRepository;
    private final io.translab.tantor.server.service.BrokerMetricsCacheService brokerMetricsCacheService;
    private final ObjectMapper objectMapper;
    private final io.translab.tantor.server.service.ActivityAlertService activityAlertService;

    @GetMapping
    public List<Map<String, Object>> listClusters() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Cluster c : clusterRepository.findByStatusNot("DELETED")) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("kafkaVersion", c.getKafkaVersion());
            m.put("mode", c.getMode());
            m.put("environment", c.getEnvironment());
            m.put("createdAt", c.getCreatedAt());
            m.put("status", c.getStatus());
            m.put("nodeCount", c.getServices() != null ? c.getServices().size() : 0);
            m.put("bootstrapServers", c.getBootstrapServers());
            result.add(m);
        }
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCluster(@PathVariable java.util.UUID id) {
        return clusterRepository.findById(id).map(c -> {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("kafkaVersion", c.getKafkaVersion());
            m.put("mode", c.getMode());
            m.put("environment", c.getEnvironment());
            m.put("createdAt", c.getCreatedAt());
            m.put("status", c.getStatus());
            m.put("nodeCount", c.getServices() != null ? c.getServices().size() : 0);
            m.put("bootstrapServers", c.getBootstrapServers());
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<io.translab.tantor.server.domain.Task>> getClusterTasks(@PathVariable java.util.UUID id) {
        return clusterRepository.findById(id).map(cluster -> {
            if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
                return ResponseEntity.ok(java.util.Collections.<io.translab.tantor.server.domain.Task>emptyList());
            }
            List<String> hostIds = cluster.getServices().stream()
                .map(io.translab.tantor.server.domain.ClusterServiceAssignment::getHostId)
                .collect(Collectors.toList());
            List<io.translab.tantor.server.domain.Task> tasks = taskRepository.findByHostIdInOrderByCreatedAtDesc(hostIds);
            return ResponseEntity.ok(tasks);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/brokers")
    public ResponseEntity<Map<String, Object>> getClusterBrokers(@PathVariable java.util.UUID id) {
        return clusterRepository.findById(id).map(cluster -> {
            List<io.translab.tantor.server.dto.BrokerSummaryDto> brokers = brokerMetricsCacheService.getBrokerSummaries(cluster);
            Map<String, Object> response = new HashMap<>();
            response.put("clusterId", cluster.getId());
            response.put("brokers", brokers);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/deploy")
    public ResponseEntity<Map<String, String>> deployCluster(@RequestBody DeployClusterRequest request) {
        
        // 1. Save Cluster to Database
        Cluster cluster = new Cluster();
        cluster.setName(request.getName());
        cluster.setKafkaVersion(request.getKafka_version());
        cluster.setMode(request.getMode());
        cluster.setEnvironment(request.getEnvironment());
        
        try {
            cluster.setConfigJson(objectMapper.writeValueAsString(request.getConfig()));
        } catch (Exception e) {
            cluster.setConfigJson("{}");
        }

        List<ClusterServiceAssignment> assignments = new ArrayList<>();
        for (ServiceAssignmentReq sa : request.getServices()) {
            ClusterServiceAssignment assign = new ClusterServiceAssignment();
            assign.setCluster(cluster);
            assign.setHostId(sa.getHost_id());
            assign.setRole(sa.getRole());
            assign.setNodeId(sa.getNode_id());
            assignments.add(assign);
        }
        cluster.setServices(assignments);
        clusterRepository.save(cluster);

        // Update host cluster_id references
        for (ServiceAssignmentReq sa : request.getServices()) {
            hostRepository.findById(sa.getHost_id()).ifPresent(host -> {
                host.setClusterId(cluster.getId());
                hostRepository.save(host);
            });
        }

        // 2. Build quorum voters string for KRaft
        // Find all controllers or broker_controllers
        List<ServiceAssignmentReq> controllers = request.getServices().stream()
                .filter(s -> s.getRole().equals("controller") || s.getRole().equals("broker_controller") || s.getRole().equals("zookeeper"))
                .collect(Collectors.toList());

        StringBuilder quorumVoters = new StringBuilder();
        int controllerPort = 9093;
        if (request.getConfig() != null && request.getConfig().containsKey("controller_port")) {
            controllerPort = (Integer) request.getConfig().get("controller_port");
        }

        for (int i = 0; i < controllers.size(); i++) {
            if (i > 0) quorumVoters.append(",");
            String hostId = controllers.get(i).getHost_id();
            String hostIp = hostId;
            io.translab.tantor.server.domain.Host h = hostRepository.findById(hostId).orElse(null);
            if (h != null && h.getIpAddresses() != null && !h.getIpAddresses().isEmpty() && !h.getIpAddresses().equals("[]")) {
                try {
                    List<String> ips = objectMapper.readValue(h.getIpAddresses(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                    if (!ips.isEmpty()) {
                        hostIp = ips.get(0);
                    }
                } catch (Exception e) {
                    // Fallback to simple string manipulation if Jackson fails
                    hostIp = h.getIpAddresses().replaceAll("\\[|\\]|\\\"", "").split(",")[0].trim();
                }
            }
            quorumVoters.append(controllers.get(i).getNode_id()).append("@").append(hostIp).append(":").append(controllerPort);
        }
        
        // 3. Dispatch tasks
        for (ServiceAssignmentReq svc : request.getServices()) {
            // Convert config to JSON string to pass as a parameter
            String configJsonStr = "{}";
            try {
                configJsonStr = objectMapper.writeValueAsString(request.getConfig());
            } catch (Exception e) {}

            String finalArtifactUrl = request.getArtifactUrl();
            if (finalArtifactUrl != null && finalArtifactUrl.contains("localhost")) {
                // Keep localhost so the agent connects via the SSH tunnel to port 8081
                finalArtifactUrl = finalArtifactUrl.replace("localhost", "127.0.0.1");
            }

            deploymentService.deployKafkaToHost(
                svc.getHost_id(),
                request.getKafka_version(),
                finalArtifactUrl,
                "", // checksum
                String.valueOf(svc.getNode_id()),
                quorumVoters.toString(),
                svc.getRole(),
                configJsonStr
            );
        }
        
        activityAlertService.logActivity("INFO", "Initialized deployment for cluster: " + request.getName(), cluster.getId());
        
        return ResponseEntity.ok(Map.of("id", cluster.getId().toString()));
    }

    @PostMapping("/external")
    public ResponseEntity<Void> addExternalCluster(@RequestBody ExternalClusterRequest request) {
        Cluster cluster = new Cluster();
        cluster.setName(request.getName());
        cluster.setKafkaVersion(request.getKafkaVersion() != null ? request.getKafkaVersion() : "Unknown");
        cluster.setMode("EXTERNAL");
        cluster.setEnvironment(request.getEnvironment());
        cluster.setBootstrapServers(request.getBootstrapServers());
        cluster.setConfigJson("{}");
        cluster.setStatus("SUCCESS");
        
        clusterRepository.save(cluster);
        
        activityAlertService.logActivity("INFO", "Connected external cluster: " + request.getName(), cluster.getId());
        
        return ResponseEntity.ok().build();
    }

    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCluster(@PathVariable java.util.UUID id) {
        java.util.Optional<Cluster> optionalCluster = clusterRepository.findById(id);
        if (optionalCluster.isPresent()) {
            Cluster cluster = optionalCluster.get();
            if ("EXTERNAL".equals(cluster.getMode())) {
                cluster.setStatus("DELETED");
                cluster.setDeletedAt(java.time.Instant.now());
                clusterRepository.save(cluster);
                activityAlertService.logActivity("INFO", "Deleted external cluster", id);
            } else {
                cluster.setStatus("DELETING");
                clusterRepository.save(cluster);
                if (cluster.getServices() != null) {
                    for (io.translab.tantor.server.domain.ClusterServiceAssignment svc : cluster.getServices()) {
                        deploymentService.deleteClusterFromHost(svc.getHostId());
                    }
                }
                activityAlertService.logActivity("INFO", "Initiated cleanup for cluster", id);
            }
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/force-delete/{id}")
    public ResponseEntity<Void> forceDeleteCluster(@PathVariable java.util.UUID id) {
        clusterRepository.findById(id).ifPresent(cluster -> {
            cluster.setStatus("DELETED");
            clusterRepository.save(cluster);
        });
        return ResponseEntity.ok().build();
    }

    @Data
    static class DeployClusterRequest {
        private String name;
        private String kafka_version;
        private String mode;
        private List<ServiceAssignmentReq> services;
        private Map<String, Object> config;
        private String environment;
        private String artifactUrl;
    }

    @Data
    static class ExternalClusterRequest {
        private String name;
        private String environment;
        private String bootstrapServers;
        private String kafkaVersion;
    }

    @Data
    static class ServiceAssignmentReq {
        private String host_id;
        private String role;
        private Integer node_id;
    }
}
