package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.DeploymentService;
import io.translab.tantor.server.service.HostStatusService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final HostStatusService hostStatusService;

    @Value("${tantor.artifact-repo.url:http://localhost:8081}")
    private String artifactRepoUrl;

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
            List<io.translab.tantor.server.domain.Task> tasks = taskRepository.findByClusterIdOrderByCreatedAtDesc(id);
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
        ResponseEntity<Map<String, String>> validationError = validateDeployRequest(request);
        if (validationError != null) {
            return validationError;
        }
        
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
            controllerPort = parseIntConfig(request.getConfig().get("controller_port"), controllerPort);
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

            String finalArtifactUrl = resolveAgentArtifactUrl(request.getArtifactUrl());

            deploymentService.deployKafkaToHost(
                cluster.getId(),
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
    public ResponseEntity<?> addExternalCluster(@RequestBody ExternalClusterRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster name is required."));
        }
        if (clusterRepository.findByNameAndStatusNot(request.getName(), "DELETED").isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A non-deleted cluster with this name already exists."));
        }

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
                markClusterDeleted(cluster);
                activityAlertService.logActivity("INFO", "Deleted external cluster", id);
            } else {
                if (initiateClusterCleanup(cluster)) {
                    activityAlertService.logActivity("INFO", "Initiated cleanup for cluster", id);
                } else {
                    markClusterDeleted(cluster);
                    activityAlertService.logActivity("INFO", "Deleted cluster with no host assignments", id);
                }
            }
            return ResponseEntity.ok().build();
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/force-delete/{id}")
    public ResponseEntity<Void> forceDeleteCluster(@PathVariable java.util.UUID id) {
        clusterRepository.findById(id).ifPresent(cluster -> {
            if ("EXTERNAL".equals(cluster.getMode()) || !initiateClusterCleanup(cluster)) {
                markClusterDeleted(cluster);
                activityAlertService.logActivity("INFO", "Force-deleted cluster without VM cleanup task", id);
            } else {
                activityAlertService.logActivity("WARN", "Force-delete requested; VM cleanup task dispatched before deleting cluster", id);
            }
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

    private ResponseEntity<Map<String, String>> validateDeployRequest(DeployClusterRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster name is required."));
        }
        if (clusterRepository.findByNameAndStatusNot(request.getName(), "DELETED").isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "A non-deleted cluster with this name already exists."));
        }
        if (request.getServices() == null || request.getServices().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one host assignment is required."));
        }

        Set<String> hostIds = new HashSet<>();
        boolean hasBroker = false;
        boolean hasController = false;
        for (ServiceAssignmentReq service : request.getServices()) {
            if (service.getHost_id() == null || service.getHost_id().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a host."));
            }
            if (service.getRole() == null || service.getRole().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a role."));
            }
            if (service.getNode_id() == null || service.getNode_id() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a positive node id."));
            }
            if (!hostIds.add(service.getHost_id())) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error",
                    "Host " + service.getHost_id() + " has more than one role assignment. Use the Broker + Controller role for a combined KRaft node."
                ));
            }

            if ("broker".equals(service.getRole()) || "broker_controller".equals(service.getRole())) {
                hasBroker = true;
            }
            if ("controller".equals(service.getRole()) || "broker_controller".equals(service.getRole())) {
                hasController = true;
            }

            io.translab.tantor.server.domain.Host host = hostRepository.findById(service.getHost_id()).orElse(null);
            if (host == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Host " + service.getHost_id() + " was not found."));
            }
            String effectiveStatus = hostStatusService.effectiveStatus(host);
            if (!"ONLINE".equalsIgnoreCase(effectiveStatus)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Host " + service.getHost_id() + " is not online. Current status: " + effectiveStatus + "."));
            }
            if (host.getClusterId() != null) {
                java.util.Optional<Cluster> activeCluster = clusterRepository.findById(host.getClusterId())
                    .filter(cluster -> !"DELETED".equals(cluster.getStatus()));
                if (activeCluster.isPresent()) {
                    return ResponseEntity.badRequest().body(Map.of(
                        "error",
                        "Host " + service.getHost_id() + " is already assigned to cluster " + activeCluster.get().getName() + ". Delete or force-delete that cluster before reusing the host."
                    ));
                }
            }
        }
        if (!hasBroker) {
            return ResponseEntity.badRequest().body(Map.of("error", "At least one broker or broker-controller node is required."));
        }
        if (!"zookeeper".equalsIgnoreCase(request.getMode()) && !hasController) {
            return ResponseEntity.badRequest().body(Map.of("error", "KRaft deployments require at least one controller or broker-controller node."));
        }
        return null;
    }

    private int parseIntConfig(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private String resolveAgentArtifactUrl(String artifactUrl) {
        if (artifactUrl == null || artifactUrl.isBlank()) {
            return artifactUrl;
        }

        String trimmed = artifactUrl.trim();
        try {
            URI uri = URI.create(trimmed);
            if (!uri.isAbsolute()) {
                return trimmed.startsWith("/api/v1/artifacts/") ? joinArtifactRepoBase(trimmed) : trimmed;
            }

            String rawPath = uri.getRawPath();
            if (rawPath != null && rawPath.startsWith("/api/v1/artifacts/")) {
                return joinArtifactRepoBase(pathAndQuery(uri));
            }
            if (isLoopbackHost(uri.getHost())) {
                return joinArtifactRepoBase(pathAndQuery(uri));
            }
        } catch (IllegalArgumentException ignored) {
            // Leave custom or malformed URLs unchanged; validation happens when the agent downloads.
        }
        return trimmed;
    }

    private String joinArtifactRepoBase(String pathAndQuery) {
        String base = artifactRepoUrl == null || artifactRepoUrl.isBlank()
                ? "http://localhost:8081"
                : artifactRepoUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (pathAndQuery == null || pathAndQuery.isBlank()) {
            return base;
        }
        String normalizedPath = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return base + normalizedPath;
    }

    private String pathAndQuery(URI uri) {
        String rawPath = uri.getRawPath() != null ? uri.getRawPath() : "";
        return uri.getRawQuery() == null ? rawPath : rawPath + "?" + uri.getRawQuery();
    }

    private boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private boolean initiateClusterCleanup(Cluster cluster) {
        if ("DELETING".equalsIgnoreCase(cluster.getStatus())) {
            return true;
        }
        if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
            return false;
        }

        cluster.setStatus("DELETING");
        clusterRepository.save(cluster);
        for (ClusterServiceAssignment svc : cluster.getServices()) {
            deploymentService.deleteClusterFromHost(cluster.getId(), svc.getHostId(), cluster.getConfigJson());
        }
        return true;
    }

    private void markClusterDeleted(Cluster cluster) {
        cluster.setStatus("DELETED");
        cluster.setDeletedAt(java.time.Instant.now());
        clearClusterHostAssignments(cluster);
        clusterRepository.save(cluster);
    }

    private void clearClusterHostAssignments(Cluster cluster) {
        if (cluster.getServices() == null) {
            return;
        }
        for (ClusterServiceAssignment service : cluster.getServices()) {
            hostRepository.findById(service.getHostId()).ifPresent(host -> {
                if (cluster.getId().equals(host.getClusterId())) {
                    host.setClusterId(null);
                    hostRepository.save(host);
                }
            });
        }
    }
}
