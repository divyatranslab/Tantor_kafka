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
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ui/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final DeploymentService deploymentService;
    private final ClusterRepository clusterRepository;
    private final io.translab.tantor.server.repository.TaskRepository taskRepository;
    private final io.translab.tantor.server.repository.HostRepository hostRepository;
    private final io.translab.tantor.server.repository.HostParcelRepository hostParcelRepository;
    private final io.translab.tantor.server.service.BrokerMetricsCacheService brokerMetricsCacheService;
    private final io.translab.tantor.server.service.ClusterOverviewService clusterOverviewService;
    private final ObjectMapper objectMapper;
    private final io.translab.tantor.server.service.ActivityAlertService activityAlertService;
    private final HostStatusService hostStatusService;
    private final io.translab.tantor.server.service.ExternalClusterService externalClusterService;
    private final io.translab.tantor.server.service.KafkaAdminService kafkaAdminService;

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
            m.put("bootstrapServers", c.getBootstrapServers());
            m.put("clusterId", c.getId().toString());
            m.put("kafkaClusterId", kafkaClusterId(c));
            m.put("config", parseConfigJson(c.getConfigJson()));
            m.put("managementLevel", managementLevel(c));
            m.put("sourceLabel", sourceLabel(c));
            m.put("accessLabel", accessLabel(c));
            List<Map<String, Object>> hosts = clusterHosts(c);
            m.put("nodeCount", hosts.isEmpty() && c.getServices() != null ? c.getServices().size() : hosts.size());
            m.put("hosts", hosts);
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
            m.put("bootstrapServers", c.getBootstrapServers());
            m.put("clusterId", c.getId().toString());
            m.put("kafkaClusterId", kafkaClusterId(c));
            m.put("config", parseConfigJson(c.getConfigJson()));
            m.put("managementLevel", managementLevel(c));
            m.put("sourceLabel", sourceLabel(c));
            m.put("accessLabel", accessLabel(c));
            List<Map<String, Object>> hosts = clusterHosts(c);
            m.put("nodeCount", hosts.isEmpty() && c.getServices() != null ? c.getServices().size() : hosts.size());
            m.put("hosts", hosts);
            return ResponseEntity.ok(m);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCluster(@PathVariable UUID id, @RequestBody UpdateClusterRequest request) {
        Cluster cluster = clusterRepository.findById(id).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();
        String name = request.getName() == null ? "" : request.getName().trim();
        String environment = request.getEnvironment() == null ? "" : request.getEnvironment().trim().toUpperCase();
        if (name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster name is required."));
        }
        boolean duplicateName = clusterRepository.findByNameAndStatusNot(name, "DELETED")
                .filter(existing -> !existing.getId().equals(id))
                .isPresent();
        if (duplicateName) {
            return ResponseEntity.badRequest().body(Map.of("error", "Another active cluster already uses this name."));
        }
        if (!Set.of("DEV", "SIT", "UAT").contains(environment)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Environment must be DEV, SIT, or UAT."));
        }
        cluster.setName(name);
        cluster.setEnvironment(environment);
        clusterRepository.save(cluster);
        activityAlertService.logActivity("INFO", "Updated cluster details for " + name, id);
        return ResponseEntity.ok(Map.of("id", id.toString(), "name", name, "environment", environment));
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

    @GetMapping("/{id}/overview")
    public ResponseEntity<io.translab.tantor.server.dto.ClusterOverviewDto> getClusterOverview(@PathVariable java.util.UUID id) {
        if (clusterRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(clusterOverviewService.getOverview(id));
    }

    @PostMapping("/deploy")
    public ResponseEntity<Map<String, String>> deployCluster(@RequestBody DeployClusterRequest request) {
        ResponseEntity<Map<String, String>> validationError = validateDeployRequest(request);
        if (validationError != null) {
            return validationError;
        }

        String deploymentMode = normalizeDeploymentMode(request.getMode());
        Map<String, Object> deploymentConfig = buildDeploymentConfig(request, deploymentMode);
        String quorumVoters = String.valueOf(deploymentConfig.getOrDefault("quorum_voters", ""));
        String bootstrapServers = String.valueOf(deploymentConfig.getOrDefault("bootstrap_servers", ""));
        
        // 1. Save Cluster to Database
        Cluster cluster = new Cluster();
        cluster.setName(request.getName());
        cluster.setKafkaVersion(request.getKafka_version());
        cluster.setMode(deploymentMode);
        cluster.setEnvironment(request.getEnvironment());
        cluster.setBootstrapServers(bootstrapServers);
        
        try {
            cluster.setConfigJson(objectMapper.writeValueAsString(deploymentConfig));
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
            assign.setConfigJson(buildServiceConfigJson(deploymentConfig, sa));
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

        // 2. Dispatch tasks
        String configJsonStr = "{}";
        try {
            configJsonStr = objectMapper.writeValueAsString(deploymentConfig);
        } catch (Exception e) {}

        String finalArtifactUrl = resolveAgentArtifactUrl(request.getArtifactUrl());
        List<ServiceAssignmentReq> deployOrder = request.getServices().stream()
                .sorted((left, right) -> Boolean.compare(!isMetadataService(left.getRole()), !isMetadataService(right.getRole())))
                .toList();
        for (ServiceAssignmentReq svc : deployOrder) {
            String serviceConfigJson = buildServiceConfigJson(deploymentConfig, svc);
            deploymentService.deployKafkaToHost(
                cluster.getId(),
                svc.getHost_id(),
                request.getKafka_version(),
                finalArtifactUrl,
                "", // checksum
                String.valueOf(svc.getNode_id()),
                quorumVoters,
                svc.getRole(),
                serviceConfigJson
            );
        }
        
        activityAlertService.logActivity("INFO", "Initialized deployment for cluster: " + request.getName(), cluster.getId());
        
        return ResponseEntity.ok(Map.of("id", cluster.getId().toString()));
    }

    @PostMapping("/{id}/nodes")
    public ResponseEntity<Map<String, String>> addNodesToCluster(@PathVariable UUID id, @RequestBody DeployClusterRequest request) {
        java.util.Optional<Cluster> optionalCluster = clusterRepository.findById(id);
        if (optionalCluster.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Cluster cluster = optionalCluster.get();
        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Use Existing cluster flow for external clusters."));
        }
        if (request.getServices() == null || request.getServices().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Select at least one node to add."));
        }

        String deploymentMode = normalizeDeploymentMode(cluster.getMode());
        ResponseEntity<Map<String, String>> validationError = validateAddNodeRequest(cluster, request, deploymentMode);
        if (validationError != null) {
            return validationError;
        }

        List<ServiceAssignmentReq> allServices = new ArrayList<>();
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment existing : cluster.getServices()) {
                ServiceAssignmentReq svc = new ServiceAssignmentReq();
                svc.setHost_id(existing.getHostId());
                svc.setRole(existing.getRole());
                svc.setNode_id(existing.getNodeId());
                allServices.add(svc);
            }
        }
        allServices.addAll(request.getServices());

        DeployClusterRequest mergedRequest = new DeployClusterRequest();
        mergedRequest.setName(cluster.getName());
        mergedRequest.setKafka_version(cluster.getKafkaVersion());
        mergedRequest.setMode(deploymentMode);
        mergedRequest.setEnvironment(cluster.getEnvironment());
        mergedRequest.setArtifactUrl(request.getArtifactUrl());
        Map<String, Object> mergedConfig = new HashMap<>(parseConfigJson(cluster.getConfigJson()));
        if (request.getConfig() != null) {
            mergedConfig.putAll(request.getConfig());
        }
        mergedRequest.setConfig(mergedConfig);
        mergedRequest.setServices(allServices);

        Map<String, Object> deploymentConfig = buildDeploymentConfig(mergedRequest, deploymentMode);
        DeployClusterRequest persistedRequest = new DeployClusterRequest();
        persistedRequest.setName(cluster.getName());
        persistedRequest.setKafka_version(cluster.getKafkaVersion());
        persistedRequest.setMode(deploymentMode);
        persistedRequest.setEnvironment(cluster.getEnvironment());
        persistedRequest.setConfig(new HashMap<>(parseConfigJson(cluster.getConfigJson())));
        persistedRequest.setServices(allServices);
        Map<String, Object> persistedClusterConfig = buildDeploymentConfig(persistedRequest, deploymentMode);
        String quorumVoters = String.valueOf(deploymentConfig.getOrDefault("quorum_voters", ""));
        String finalArtifactUrl = resolveAgentArtifactUrl(request.getArtifactUrl());

        if (cluster.getServices() == null) {
            cluster.setServices(new ArrayList<>());
        }
        for (ServiceAssignmentReq sa : request.getServices()) {
            ClusterServiceAssignment assign = new ClusterServiceAssignment();
            assign.setCluster(cluster);
            assign.setHostId(sa.getHost_id());
            assign.setRole(sa.getRole());
            assign.setNodeId(sa.getNode_id());
            assign.setConfigJson(buildServiceConfigJson(deploymentConfig, sa));
            cluster.getServices().add(assign);
        }
        cluster.setStatus("RUNNING");
        cluster.setBootstrapServers(String.valueOf(persistedClusterConfig.getOrDefault("bootstrap_servers", cluster.getBootstrapServers())));
        try {
            cluster.setConfigJson(objectMapper.writeValueAsString(persistedClusterConfig));
        } catch (Exception e) {
            // keep existing config if serialization fails
        }
        clusterRepository.save(cluster);

        for (ServiceAssignmentReq sa : request.getServices()) {
            hostRepository.findById(sa.getHost_id()).ifPresent(host -> {
                host.setClusterId(cluster.getId());
                hostRepository.save(host);
            });
        }

        List<ServiceAssignmentReq> deployOrder = request.getServices().stream()
                .sorted((left, right) -> Boolean.compare(!isMetadataService(left.getRole()), !isMetadataService(right.getRole())))
                .toList();
        for (ServiceAssignmentReq svc : deployOrder) {
            deploymentService.deployKafkaToHost(
                    cluster.getId(),
                    svc.getHost_id(),
                    cluster.getKafkaVersion(),
                    finalArtifactUrl,
                    "",
                    String.valueOf(svc.getNode_id()),
                    quorumVoters,
                    svc.getRole(),
                    buildServiceConfigJson(deploymentConfig, svc)
            );
        }

        activityAlertService.logActivity("INFO", "Scheduled node addition for cluster: " + cluster.getName(), cluster.getId());
        return ResponseEntity.ok(Map.of("id", cluster.getId().toString(), "status", "scheduled"));
    }

    @PostMapping("/external")
    public ResponseEntity<?> addExternalCluster(@RequestBody ExternalClusterRequest request) {
        io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport report =
                new io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport();
        report.setName(request.getName());
        report.setEnvironment(request.getEnvironment());
        report.setBootstrapServers(request.getBootstrapServers());
        report.setKafkaVersion(request.getKafkaVersion());
        report.setKafkaClusterId(request.getKafkaClusterId());
        report.setKafkaMode(request.getKafkaMode());
        report.setSecurity(request.getSecurity());
        report.setBrokerCount(request.getBrokerCount());
        report.setNodeId(request.getNodeId());
        report.setRunning(request.isRunning());
        report.setInstallPath(request.getInstallPath());
        report.setLogDirs(request.getLogDirs());
        report.setHostname(request.getHostname());
        return ResponseEntity.ok(externalClusterService.recordDiscoveryReport(report));
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

    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Map<String, String>> upgradeCluster(@PathVariable java.util.UUID id, @RequestBody UpgradeClusterRequest request) {
        java.util.Optional<Cluster> optionalCluster = clusterRepository.findById(id);
        if (optionalCluster.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Cluster cluster = optionalCluster.get();
        String targetVersion = request == null ? null : request.getTargetVersion();
        if (targetVersion == null || targetVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Target Kafka version is required."));
        }
        targetVersion = targetVersion.trim();

        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            return ResponseEntity.badRequest().body(Map.of("error", "External clusters cannot be upgraded by Tantor."));
        }
        if (!"SUCCESS".equalsIgnoreCase(cluster.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster must be active before upgrade. Current status: " + cluster.getStatus() + "."));
        }
        if (targetVersion.equals(cluster.getKafkaVersion())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster is already on Kafka " + targetVersion + "."));
        }
        if ("zookeeper".equalsIgnoreCase(cluster.getMode()) && !isZooKeeperSupported(targetVersion)) {
            return ResponseEntity.badRequest().body(Map.of("error", "ZooKeeper deployments are not supported for Kafka 4.0.0 and newer."));
        }
        if (cluster.getServices() == null || cluster.getServices().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cluster has no host assignments."));
        }

        for (ClusterServiceAssignment service : cluster.getServices()) {
            String hostId = service.getHostId();
            io.translab.tantor.server.domain.Host host = hostRepository.findById(hostId).orElse(null);
            if (host == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Host " + hostId + " was not found."));
            }
            String effectiveStatus = hostStatusService.effectiveStatus(host);
            if (!"ONLINE".equalsIgnoreCase(effectiveStatus)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Host " + hostId + " is not online. Current status: " + effectiveStatus + "."));
            }
            String finalTargetVersion = targetVersion;
            boolean activeOnHost = hostParcelRepository.findByHostIdAndServiceTypeAndActiveTrue(hostId, "KAFKA").stream()
                    .anyMatch(parcel -> finalTargetVersion.equals(parcel.getVersion()));
            if (!activeOnHost) {
                return ResponseEntity.badRequest().body(Map.of("error", "Kafka " + targetVersion + " must be active as a parcel on host " + hostId + " before upgrade."));
            }
        }

        String previousVersion = cluster.getKafkaVersion();
        cluster.setStatus("RUNNING");
        clusterRepository.save(cluster);

        for (ClusterServiceAssignment service : cluster.getServices()) {
            deploymentService.upgradeKafkaOnHost(
                cluster.getId(),
                service.getHostId(),
                previousVersion,
                targetVersion,
                service.getNodeId() == null ? "1" : String.valueOf(service.getNodeId()),
                service.getRole(),
                cluster.getConfigJson()
            );
        }

        activityAlertService.logActivity("INFO", "Initialized Kafka upgrade to " + targetVersion + " for cluster: " + cluster.getName(), cluster.getId());
        return ResponseEntity.ok(Map.of("status", "scheduled", "targetVersion", targetVersion));
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
        private String kafkaClusterId;
        private String kafkaMode;
        private String security;
        private int brokerCount = 1;
        private Integer nodeId;
        private boolean isRunning = true;
        private String installPath;
        private String logDirs;
        private String hostname;
    }

    @Data
    static class UpdateClusterRequest {
        private String name;
        private String environment;
    }

    @Data
    static class UpgradeClusterRequest {
        private String targetVersion;
    }

    @Data
    static class ServiceAssignmentReq {
        private String host_id;
        private String role;
        private Integer node_id;
        private String configuration_mode;
        private String properties_template;
        private String heap_size;
    }

    private String buildServiceConfigJson(Map<String, Object> deploymentConfig, ServiceAssignmentReq svc) {
        Map<String, Object> serviceConfig = new HashMap<>(deploymentConfig);
        if (svc.getConfiguration_mode() != null && !svc.getConfiguration_mode().isBlank()) {
            serviceConfig.put("configuration_mode", svc.getConfiguration_mode());
        }
        if (svc.getHeap_size() != null && !svc.getHeap_size().isBlank()) {
            serviceConfig.put("heap_size", svc.getHeap_size());
        }
        if (svc.getProperties_template() != null && !svc.getProperties_template().isBlank()) {
            String role = svc.getRole();
            if ("controller".equals(role)) {
                serviceConfig.put("controller_properties_template", svc.getProperties_template());
            } else if ("zookeeper".equals(role)) {
                serviceConfig.put("zookeeper_properties_template", svc.getProperties_template());
            } else if ("broker".equals(role)) {
                serviceConfig.put("broker_properties_template", svc.getProperties_template());
            } else {
                serviceConfig.put("server_properties_template", svc.getProperties_template());
            }
        }
        try {
            return objectMapper.writeValueAsString(serviceConfig);
        } catch (Exception e) {
            return "{}";
        }
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

        String deploymentMode = normalizeDeploymentMode(request.getMode());
        boolean zookeeperMode = "zookeeper".equals(deploymentMode);
        if (zookeeperMode && !isZooKeeperSupported(request.getKafka_version())) {
            return ResponseEntity.badRequest().body(Map.of("error", "ZooKeeper deployments are not supported for Kafka 4.0.0 and newer."));
        }

        Set<String> assignmentKeys = new HashSet<>();
        Set<Integer> nodeIds = new HashSet<>();
        boolean hasBroker = false;
        int brokerCount = 0;
        boolean hasController = false;
        boolean hasZooKeeper = false;
        for (ServiceAssignmentReq service : request.getServices()) {
            if (service.getHost_id() == null || service.getHost_id().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a host."));
            }
            if (service.getRole() == null || service.getRole().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a role."));
            }
            if (!isRoleAllowedForMode(service.getRole(), deploymentMode)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Role " + service.getRole() + " is not valid for " + deploymentMode + " deployments."));
            }
            if (service.getNode_id() == null || service.getNode_id() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a positive node id."));
            }
            if (!nodeIds.add(service.getNode_id())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Node id " + service.getNode_id() + " is assigned more than once."));
            }
            for (String roleKind : serviceRoleKinds(service.getRole())) {
                String assignmentKey = service.getHost_id() + "::" + roleKind;
                if (!assignmentKeys.add(assignmentKey)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Host " + service.getHost_id() + " has duplicate " + roleKind + " service assignments."));
                }
            }

            if (isBrokerRole(service.getRole())) {
                hasBroker = true;
                brokerCount++;
            }
            if (isControllerRole(service.getRole())) {
                hasController = true;
            }
            if (isZooKeeperRole(service.getRole())) {
                hasZooKeeper = true;
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
            return ResponseEntity.badRequest().body(Map.of("error", "At least one broker node is required."));
        }
        int replicationFactor = parseIntConfig(request.getConfig() == null ? null : request.getConfig().get("replication_factor"), 1);
        int minInSyncReplicas = parseIntConfig(request.getConfig() == null ? null : request.getConfig().get("min_insync_replicas"), 1);
        if (replicationFactor < 1 || replicationFactor > brokerCount) {
            return ResponseEntity.badRequest().body(Map.of("error", "Replication factor must be between 1 and the selected broker count (" + brokerCount + ")."));
        }
        if (minInSyncReplicas < 1 || minInSyncReplicas > replicationFactor) {
            return ResponseEntity.badRequest().body(Map.of("error", "Minimum in-sync replicas must be between 1 and the replication factor (" + replicationFactor + ")."));
        }
        if (zookeeperMode && !hasZooKeeper) {
            return ResponseEntity.badRequest().body(Map.of("error", "ZooKeeper deployments require at least one ZooKeeper or broker-zookeeper node."));
        }
        if (!zookeeperMode && !hasController) {
            return ResponseEntity.badRequest().body(Map.of("error", "KRaft deployments require at least one controller or broker-controller node."));
        }
        return null;
    }

    private ResponseEntity<Map<String, String>> validateAddNodeRequest(Cluster cluster, DeployClusterRequest request, String deploymentMode) {
        Set<String> assignmentKeys = new HashSet<>();
        Set<Integer> nodeIds = new HashSet<>();
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment existing : cluster.getServices()) {
                if (existing.getNodeId() != null) {
                    nodeIds.add(existing.getNodeId());
                }
                for (String roleKind : serviceRoleKinds(existing.getRole())) {
                    assignmentKeys.add(existing.getHostId() + "::" + roleKind);
                }
            }
        }

        for (ServiceAssignmentReq service : request.getServices()) {
            if (service.getHost_id() == null || service.getHost_id().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a host."));
            }
            if (service.getRole() == null || service.getRole().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a role."));
            }
            if (!isRoleAllowedForMode(service.getRole(), deploymentMode)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Role " + service.getRole() + " is not valid for " + deploymentMode + " deployments."));
            }
            if (!"broker".equals(service.getRole())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Online Add Node currently supports broker services only. Controller and ZooKeeper voter changes require a dedicated quorum reconfiguration workflow."));
            }
            if (service.getNode_id() == null || service.getNode_id() <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Every service assignment must include a positive node id."));
            }
            if (!nodeIds.add(service.getNode_id())) {
                return ResponseEntity.badRequest().body(Map.of("error", "Node id " + service.getNode_id() + " is already used in this cluster."));
            }
            for (String roleKind : serviceRoleKinds(service.getRole())) {
                String assignmentKey = service.getHost_id() + "::" + roleKind;
                if (!assignmentKeys.add(assignmentKey)) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Host " + service.getHost_id() + " already has a " + roleKind + " service in this cluster."));
                }
            }

            io.translab.tantor.server.domain.Host host = hostRepository.findById(service.getHost_id()).orElse(null);
            if (host == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Host " + service.getHost_id() + " was not found."));
            }
            String effectiveStatus = hostStatusService.effectiveStatus(host);
            if (!"ONLINE".equalsIgnoreCase(effectiveStatus)) {
                return ResponseEntity.badRequest().body(Map.of("error", "Host " + service.getHost_id() + " is not online. Current status: " + effectiveStatus + "."));
            }
            if (host.getClusterId() != null && !cluster.getId().equals(host.getClusterId())) {
                java.util.Optional<Cluster> activeCluster = clusterRepository.findById(host.getClusterId())
                        .filter(existing -> !"DELETED".equals(existing.getStatus()));
                if (activeCluster.isPresent()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error",
                            "Host " + service.getHost_id() + " is already assigned to cluster " + activeCluster.get().getName() + "."
                    ));
                }
            }
        }
        return null;
    }

    private Map<String, Object> buildDeploymentConfig(DeployClusterRequest request, String deploymentMode) {
        Map<String, Object> config = new HashMap<>();
        if (request.getConfig() != null) {
            config.putAll(request.getConfig());
        }

        config.put("mode", deploymentMode);
        config.put("version", request.getKafka_version());
        config.putIfAbsent("kafka_install_dir", "/opt");
        int listenerPort = parseIntConfig(config.get("listener_port"), 9092);
        config.put("listener_port", listenerPort);
        config.put("bootstrap_servers", buildBootstrapServers(request.getServices(), listenerPort));
        if ("zookeeper".equals(deploymentMode)) {
            int zookeeperPort = parseIntConfig(config.get("zookeeper_port"), parseIntConfig(config.get("controller_port"), 2181));
            int zookeeperPeerPort = parseIntConfig(config.get("zookeeper_peer_port"), 2888);
            int zookeeperElectionPort = parseIntConfig(config.get("zookeeper_election_port"), 3888);
            config.put("zookeeper_port", zookeeperPort);
            config.put("controller_port", zookeeperPort);
            config.put("zookeeper_connect", buildZooKeeperConnect(request.getServices(), zookeeperPort));
            config.put("zookeeper_peer_port", zookeeperPeerPort);
            config.put("zookeeper_election_port", zookeeperElectionPort);
            String zookeeperServers = buildZooKeeperServers(request.getServices(), zookeeperPeerPort, zookeeperElectionPort);
            if (!zookeeperServers.isBlank()) {
                config.put("zookeeper_servers", zookeeperServers);
            }
        } else {
            int controllerPort = parseIntConfig(config.get("controller_port"), 9093);
            config.put("controller_port", controllerPort);
            config.put("quorum_voters", buildQuorumVoters(request.getServices(), controllerPort));
            config.put("bootstrap_servers", buildBootstrapServers(request.getServices(), listenerPort));
            config.putIfAbsent("cluster_uuid", generateKafkaClusterUuid());
        }
        config.put("service_topology", buildServiceTopology(request.getServices(), deploymentMode, config));
        return config;
    }

    private String generateKafkaClusterUuid() {
        UUID uuid = UUID.randomUUID();
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(uuid.getMostSignificantBits());
        buffer.putLong(uuid.getLeastSignificantBits());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    private String buildBootstrapServers(List<ServiceAssignmentReq> services, int listenerPort) {
        return services.stream()
                .filter(service -> isBrokerRole(service.getRole()))
                .map(service -> resolveHostAddress(service.getHost_id()) + ":" + listenerPort)
                .collect(Collectors.joining(","));
    }

    private List<Map<String, Object>> buildServiceTopology(List<ServiceAssignmentReq> services, String deploymentMode, Map<String, Object> config) {
        List<Map<String, Object>> topology = new ArrayList<>();
        String installDir = activeKafkaInstallDir(config);
        String dataDir = defaultKafkaDataDir(config);
        String listenerPort = String.valueOf(config.getOrDefault("listener_port", "9092"));
        String controllerPort = String.valueOf(config.getOrDefault("controller_port", "9093"));
        for (ServiceAssignmentReq service : services) {
            String role = service.getRole();
            String hostAddress = resolveHostAddress(service.getHost_id());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("hostId", service.getHost_id());
            item.put("hostAddress", hostAddress);
            item.put("role", role);
            item.put("nodeId", service.getNode_id());
            item.put("serviceName", systemdServiceName(role));
            item.put("configFile", configFileForRole(role, deploymentMode, installDir));
            item.put("listenerPort", isBrokerRole(role) ? listenerPort : "");
            item.put("controllerPort", isControllerRole(role) || isZooKeeperRole(role) ? controllerPort : "");
            item.put("logDirs", isBrokerRole(role) ? brokerLogDirs(config, dataDir) : "");
            item.put("metadataLogDir", metadataLogDirForRole(role, config, dataDir));
            topology.add(item);
        }
        return topology;
    }
    private String buildQuorumVoters(List<ServiceAssignmentReq> services, int controllerPort) {
        StringBuilder quorumVoters = new StringBuilder();
        List<ServiceAssignmentReq> controllers = services.stream()
                .filter(service -> isControllerRole(service.getRole()))
                .toList();

        for (int i = 0; i < controllers.size(); i++) {
            if (i > 0) quorumVoters.append(",");
            ServiceAssignmentReq controller = controllers.get(i);
            quorumVoters
                    .append(controller.getNode_id())
                    .append("@")
                    .append(resolveHostAddress(controller.getHost_id()))
                    .append(":")
                    .append(controllerPort);
        }
        return quorumVoters.toString();
    }

    private String buildZooKeeperConnect(List<ServiceAssignmentReq> services, int zookeeperPort) {
        return services.stream()
                .filter(service -> isZooKeeperRole(service.getRole()))
                .map(service -> resolveHostAddress(service.getHost_id()) + ":" + zookeeperPort)
                .collect(Collectors.joining(","));
    }

    private String buildZooKeeperServers(List<ServiceAssignmentReq> services, int peerPort, int electionPort) {
        List<ServiceAssignmentReq> zookeeperNodes = services.stream()
                .filter(service -> isZooKeeperRole(service.getRole()))
                .toList();
        if (zookeeperNodes.size() <= 1) {
            return "";
        }
        return zookeeperNodes.stream()
                .map(service -> "server." + service.getNode_id() + "=" + resolveHostAddress(service.getHost_id()) + ":" + peerPort + ":" + electionPort)
                .collect(Collectors.joining("\n"));
    }

    private String resolveHostAddress(String hostId) {
        String hostIp = hostId;
        io.translab.tantor.server.domain.Host h = hostRepository.findById(hostId).orElse(null);
        if (h != null && h.getIpAddresses() != null && !h.getIpAddresses().isEmpty() && !h.getIpAddresses().equals("[]")) {
            try {
                List<String> ips = objectMapper.readValue(h.getIpAddresses(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                if (!ips.isEmpty()) {
                    hostIp = ips.get(0);
                }
            } catch (Exception e) {
                hostIp = h.getIpAddresses().replaceAll("\\[|\\]|\\\"", "").split(",")[0].trim();
            }
        }
        return hostIp;
    }

    private List<String> serviceRoleKinds(String role) {
        if ("broker_controller".equals(role)) {
            return List.of("broker", "controller");
        }
        if ("broker_zookeeper".equals(role)) {
            return List.of("broker", "zookeeper");
        }
        return List.of(role);
    }

    private String systemdServiceName(String role) {
        if ("controller".equals(role)) return "controller";
        if ("zookeeper".equals(role)) return "zookeeper";
        if ("broker_controller".equals(role)) return "kafka";
        if ("broker_zookeeper".equals(role)) return "kafka";
        return "broker";
    }

    private String configFileForRole(String role, String deploymentMode, String installDir) {
        if ("zookeeper".equalsIgnoreCase(deploymentMode)) {
            if ("zookeeper".equals(role)) return installDir + "/config/zookeeper.properties";
            return installDir + "/config/server.properties";
        }
        if ("controller".equals(role)) return installDir + "/config/kraft/controller.properties";
        if ("broker".equals(role)) return installDir + "/config/kraft/broker.properties";
        return installDir + "/config/kraft/server.properties";
    }

    private String activeKafkaInstallDir(Map<String, Object> config) {
        String configured = String.valueOf(config.getOrDefault("kafka_install_base_dir", config.getOrDefault("kafka_install_dir", "/opt"))).trim();
        if (configured.isBlank()) configured = "/opt";
        configured = trimTrailingSlash(configured);
        if (configured.endsWith("/kafka")) return configured;
        String leaf = configured.substring(configured.lastIndexOf('/') + 1);
        if (leaf.startsWith("kafka_")) {
            int lastSlash = configured.lastIndexOf('/');
            return (lastSlash <= 0 ? "" : configured.substring(0, lastSlash)) + "/kafka";
        }
        return configured + "/kafka";
    }

    private String defaultKafkaDataDir(Map<String, Object> config) {
        String configured = String.valueOf(config.getOrDefault("kafka_install_base_dir", config.getOrDefault("kafka_install_dir", "/opt"))).trim();
        if (configured.isBlank()) configured = "/opt";
        configured = trimTrailingSlash(configured);
        if ("/opt".equals(configured) || "/".equals(configured)) return "/data/kafka";
        if (configured.endsWith("/kafka")) {
            int lastSlash = configured.lastIndexOf('/');
            configured = lastSlash <= 0 ? "/" : configured.substring(0, lastSlash);
        }
        return trimTrailingSlash(configured) + "/kafka-data";
    }

    private String trimTrailingSlash(String value) {
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String brokerLogDirs(Map<String, Object> config, String dataDir) {
        Object configured = config.get("log_dirs");
        if (configured != null && !String.valueOf(configured).isBlank()) return String.valueOf(configured);
        return dataDir + "/broker-data";
    }

    private String metadataLogDirForRole(String role, Map<String, Object> config, String dataDir) {
        Object configured = config.get("metadata_log_dir");
        if (configured != null && !String.valueOf(configured).isBlank()) return String.valueOf(configured);
        if ("controller".equals(role)) return dataDir + "/controller-data/metadata";
        if (isControllerRole(role) && !isBrokerRole(role)) return dataDir + "/controller-data/metadata";
        return dataDir + "/broker-metadata";
    }
    private boolean isRoleAllowedForMode(String role, String deploymentMode) {
        if ("zookeeper".equals(deploymentMode)) {
            return "broker".equals(role) || "zookeeper".equals(role) || "broker_zookeeper".equals(role);
        }
        return "broker".equals(role) || "controller".equals(role) || "broker_controller".equals(role);
    }

    private boolean isBrokerRole(String role) {
        return "broker".equals(role) || "broker_controller".equals(role) || "broker_zookeeper".equals(role);
    }

    private boolean isControllerRole(String role) {
        return "controller".equals(role) || "broker_controller".equals(role);
    }

    private boolean isZooKeeperRole(String role) {
        return "zookeeper".equals(role) || "broker_zookeeper".equals(role);
    }

    private boolean isMetadataService(String role) {
        return isControllerRole(role) || isZooKeeperRole(role);
    }

    private String normalizeDeploymentMode(String mode) {
        return "zookeeper".equalsIgnoreCase(mode) ? "zookeeper" : "kraft";
    }

    private boolean isZooKeeperSupported(String kafkaVersion) {
        int[] version = parseKafkaVersion(kafkaVersion);
        return version[0] < 4;
    }

    private String externalMetadataValue(Cluster cluster, String key) {
        if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode()) || cluster.getConfigJson() == null || cluster.getConfigJson().isBlank()) {
            return null;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(cluster.getConfigJson(), Map.class);
            Object value = metadata.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String kafkaClusterId(Cluster cluster) {
        String externalId = externalMetadataValue(cluster, "kafkaClusterId");
        if (externalId != null && !externalId.isBlank()) {
            return externalId;
        }
        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())
                || !("SUCCESS".equalsIgnoreCase(cluster.getStatus()) || "ACTIVE".equalsIgnoreCase(cluster.getStatus()))) {
            return "";
        }
        return kafkaAdminService.getKafkaClusterId(cluster.getId());
    }

    private Map<String, Object> parseConfigJson(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(configJson, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String managementLevel(Cluster cluster) {
        String level = externalMetadataValue(cluster, "managementMode");
        if (level != null && !level.isBlank()) {
            return level;
        }
        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            return externalClusterService.isAgentManaged(cluster) ? "AGENT_MANAGED" : "BOOTSTRAP_ONLY";
        }
        return "INTERNAL_MANAGED";
    }

    private String sourceLabel(Cluster cluster) {
        return "EXTERNAL".equalsIgnoreCase(cluster.getMode()) ? "External" : "Internal";
    }

    private String accessLabel(Cluster cluster) {
        if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode())) {
            return "Full access";
        }
        return "AGENT_MANAGED".equalsIgnoreCase(managementLevel(cluster))
                ? "Fully managed"
                : "Metadata available";
    }

    private List<Map<String, Object>> clusterHosts(Cluster cluster) {
        List<Map<String, Object>> hosts = new ArrayList<>();
        if (cluster.getServices() != null) {
            for (ClusterServiceAssignment service : cluster.getServices()) {
                hostRepository.findById(service.getHostId()).ifPresent(host -> {
                    if (!"EXTERNAL".equalsIgnoreCase(cluster.getMode())
                            || "ONLINE".equalsIgnoreCase(hostStatusService.effectiveStatus(host))) {
                        hosts.add(hostSummary(service, host));
                    }
                });
            }
        }

        if ("EXTERNAL".equalsIgnoreCase(cluster.getMode()) && hosts.isEmpty()) {
            Set<String> seen = hosts.stream()
                    .map(item -> String.valueOf(item.getOrDefault("bootstrap", "")))
                    .collect(Collectors.toSet());
            for (io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerRecord broker : externalClusterService.brokerRecords(cluster)) {
                String bootstrap = broker.getBootstrap() == null ? "" : broker.getBootstrap();
                if (!seen.contains(bootstrap)) {
                    hosts.add(externalBrokerSummary(broker));
                }
            }
        }
        return hosts;
    }

    private Map<String, Object> hostSummary(ClusterServiceAssignment service, io.translab.tantor.server.domain.Host host) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hostId", host.getId());
        summary.put("hostname", host.getHostname());
        summary.put("ipAddress", firstIp(host.getIpAddresses()));
        summary.put("status", hostStatusService.effectiveStatus(host));
        summary.put("role", service.getRole());
        summary.put("nodeId", service.getNodeId());
        summary.put("lastHeartbeat", host.getLastHeartbeat());
        summary.put("diskUsedGb", host.getDiskUsedGb());
        summary.put("diskTotalGb", host.getDiskTotalGb());
        summary.put("bootstrap", "");
        return summary;
    }

    private Map<String, Object> externalBrokerSummary(io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerRecord broker) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hostId", "");
        summary.put("hostname", broker.getHostname());
        summary.put("ipAddress", extractBootstrapHost(broker.getBootstrap()));
        summary.put("status", broker.isRunning() ? "ONLINE" : "OFFLINE");
        summary.put("role", broker.getRole());
        summary.put("lastHeartbeat", parseOffsetDateTime(broker.getLastSeen()));
        summary.put("diskUsedGb", broker.getDiskUsedGb());
        summary.put("diskTotalGb", broker.getDiskTotalGb());
        summary.put("bootstrap", broker.getBootstrap());
        return summary;
    }

    private OffsetDateTime parseOffsetDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String firstIp(String ipAddresses) {
        if (ipAddresses == null || ipAddresses.isBlank() || "[]".equals(ipAddresses)) {
            return "";
        }
        try {
            List<String> ips = objectMapper.readValue(ipAddresses, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return ips.isEmpty() ? "" : ips.get(0);
        } catch (Exception ignored) {
            return ipAddresses.replaceAll("\\[|\\]|\\\"", "").split(",")[0].trim();
        }
    }

    private String extractBootstrapHost(String bootstrap) {
        if (bootstrap == null || bootstrap.isBlank()) {
            return "";
        }
        String first = bootstrap.split(",")[0].trim();
        int portIndex = first.lastIndexOf(':');
        return portIndex > 0 ? first.substring(0, portIndex) : first;
    }

    private int[] parseKafkaVersion(String kafkaVersion) {
        int[] fallback = new int[] {0, 0, 0};
        if (kafkaVersion == null || kafkaVersion.isBlank()) {
            return fallback;
        }
        String[] parts = kafkaVersion.trim().split("\\.");
        int[] parsed = new int[] {0, 0, 0};
        for (int i = 0; i < Math.min(parts.length, 3); i++) {
            try {
                parsed[i] = Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
            } catch (NumberFormatException e) {
                parsed[i] = 0;
            }
        }
        return parsed;
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
        Set<String> cleanupHosts = new HashSet<>();
        for (ClusterServiceAssignment svc : cluster.getServices()) {
            if (cleanupHosts.add(svc.getHostId())) {
                deploymentService.deleteClusterFromHost(cluster.getId(), svc.getHostId(), cluster.getKafkaVersion(), cluster.getConfigJson());
            }
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
