package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.*;
import io.translab.tantor.server.web.ClusterController.DeployClusterRequest;
import io.translab.tantor.server.web.ClusterController.ServiceAssignmentReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import java.util.*;
import java.util.stream.Collectors;
import java.time.OffsetDateTime;
import java.nio.ByteBuffer;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterValidationService {

    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final HostStatusService hostStatusService;
    private final ObjectMapper objectMapper;

    public ResponseEntity<Map<String, Object>> validateKraftTopology(DeployClusterRequest request) {
        if (!"kraft".equals(normalizeDeploymentMode(request.getMode()))) {
            return ResponseEntity.badRequest().body(Map.of("errors", List.of("KRaft validation requires KRaft mode.")));
        }
        if (request.getServices() == null || request.getServices().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("errors", List.of("Select at least one service.")));
        }
        Map<String, Object> config = buildDeploymentConfig(request, "kraft");
        Map<String, Object> report = kraftValidationReport(request, config);
        report.put("generatedConfig", Map.of(
                "cluster_uuid", String.valueOf(config.get("cluster_uuid")),
                "kraft_quorum_mode", String.valueOf(config.get("kraft_quorum_mode")),
                "quorum_voters", String.valueOf(config.getOrDefault("quorum_voters", "")),
                "quorum_bootstrap_servers", String.valueOf(config.getOrDefault("quorum_bootstrap_servers", "")),
                "initial_controllers", String.valueOf(config.getOrDefault("initial_controllers", ""))
        ));
        return ResponseEntity.ok(report);
    }


    public ResponseEntity<Map<String, String>> validateDeployRequest(DeployClusterRequest request) {
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
        if (!zookeeperMode) {
            Map<String, Object> config = buildDeploymentConfig(request, deploymentMode);
            Map<String, Object> report = kraftValidationReport(request, config);
            @SuppressWarnings("unchecked")
            List<String> errors = (List<String>) report.get("errors");
            @SuppressWarnings("unchecked")
            List<String> warnings = (List<String>) report.get("warnings");
            if (!errors.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", errors.get(0)));
            }
            if (!warnings.isEmpty() && !request.isAcknowledge_kraft_risk()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error",
                        "Acknowledge the KRaft availability warning before deployment: " + warnings.get(0)
                ));
            }
        }
        return null;
    }


    public ResponseEntity<Map<String, String>> validateAddNodeRequest(Cluster cluster, DeployClusterRequest request, String deploymentMode) {
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


    public Map<String, Object> buildDeploymentConfig(DeployClusterRequest request, String deploymentMode) {
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
            String quorumMode = normalizedKraftQuorumMode(request.getKafka_version(), config.get("kraft_quorum_mode"));
            String quorumVoters = buildQuorumVoters(request.getServices(), controllerPort);
            String quorumBootstrapServers = buildControllerBootstrapServers(request.getServices(), controllerPort);
            config.put("kraft_quorum_mode", quorumMode);
            config.put("quorum_bootstrap_servers", quorumBootstrapServers);
            config.put("controller_endpoints", quorumBootstrapServers);
            if ("dynamic".equals(quorumMode)) {
                config.remove("quorum_voters");
                config.putIfAbsent("initial_controllers", buildInitialControllers(request.getServices(), controllerPort));
            } else {
                config.put("quorum_voters", quorumVoters);
                config.remove("initial_controllers");
            }
            config.put("bootstrap_servers", buildBootstrapServers(request.getServices(), listenerPort));
            config.putIfAbsent("cluster_uuid", generateKafkaClusterUuid());
        }
        config.put("service_topology", buildServiceTopology(request.getServices(), deploymentMode, config));
        return config;
    }


    public String generateKafkaClusterUuid() {
        while (true) {
            UUID uuid = UUID.randomUUID();
            ByteBuffer buffer = ByteBuffer.allocate(16);
            buffer.putLong(uuid.getMostSignificantBits());
            buffer.putLong(uuid.getLeastSignificantBits());
            String id = Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
            if (!id.isBlank() && Character.isLetterOrDigit(id.charAt(0))) {
                return id;
            }
        }
    }


    public String buildBootstrapServers(List<ServiceAssignmentReq> services, int listenerPort) {
        return services.stream()
                .filter(service -> isBrokerRole(service.getRole()))
                .map(service -> resolveHostAddress(service.getHost_id()) + ":" + (service.getListener_port() != null ? service.getListener_port() : listenerPort))
                .collect(Collectors.joining(","));
    }


    public List<Map<String, Object>> buildServiceTopology(List<ServiceAssignmentReq> services, String deploymentMode, Map<String, Object> config) {
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
            item.put("configFile", configFileForRole(role, deploymentMode,
                    String.valueOf(config.getOrDefault("version", config.getOrDefault("kafka_version", "0"))), installDir));
            item.put("listenerPort", isBrokerRole(role) ? (service.getListener_port() != null ? String.valueOf(service.getListener_port()) : listenerPort) : "");
            item.put("controllerPort", isControllerRole(role) || isZooKeeperRole(role) ? (service.getController_port() != null ? String.valueOf(service.getController_port()) : controllerPort) : "");
            item.put("logDirs", isBrokerRole(role) ? brokerLogDirs(config, dataDir) : "");
            item.put("metadataLogDir", metadataLogDirForRole(role, config, dataDir));
            topology.add(item);
        }
        return topology;
    }


    public String buildQuorumVoters(List<ServiceAssignmentReq> services, int controllerPort) {
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
                    .append(controller.getController_port() != null ? controller.getController_port() : controllerPort);
        }
        return quorumVoters.toString();
    }


    public String buildControllerBootstrapServers(List<ServiceAssignmentReq> services, int controllerPort) {
        return services.stream()
                .filter(service -> isControllerRole(service.getRole()))
                .map(service -> resolveHostAddress(service.getHost_id()) + ":" + (service.getController_port() != null ? service.getController_port() : controllerPort))
                .collect(Collectors.joining(","));
    }


    public String buildInitialControllers(List<ServiceAssignmentReq> services, int controllerPort) {
        return services.stream()
                .filter(service -> isControllerRole(service.getRole()))
                .map(service -> service.getNode_id() + "@" + resolveHostAddress(service.getHost_id()) + ":"
                        + (service.getController_port() != null ? service.getController_port() : controllerPort) + ":" + generateKafkaClusterUuid())
                .collect(Collectors.joining(","));
    }


    public String normalizedKraftQuorumMode(String kafkaVersion, Object configured) {
        String requested = configured == null ? "" : String.valueOf(configured).trim().toLowerCase();
        if ("static".equals(requested) || "dynamic".equals(requested)) return requested;
        return "static";
    }


    public Map<String, Object> kraftValidationReport(DeployClusterRequest request, Map<String, Object> config) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        Set<String> controllerEndpoints = new HashSet<>();
        int controllerCount = 0;
        int brokerCount = 0;
        int controllerPort = parseIntConfig(config.get("controller_port"), 9093);

        for (ServiceAssignmentReq service : request.getServices()) {
            String role = service.getRole() == null ? "" : service.getRole();
            String address = resolveHostAddress(service.getHost_id());
            Integer nodeId = service.getNode_id();
            if (nodeId == null || nodeId < 1 || !ids.add(nodeId)) {
                errors.add("Every KRaft process must have a unique positive node.id. Duplicate or invalid id: " + nodeId + ".");
            }
            if (!isRoleAllowedForMode(role, "kraft")) {
                errors.add("Invalid KRaft role " + role + " on " + service.getHost_id() + ".");
            }
            if (isControllerRole(role)) {
                controllerCount++;
                String endpoint = address + ":" + (service.getController_port() != null ? service.getController_port() : controllerPort);
                if (!controllerEndpoints.add(endpoint)) {
                    errors.add("Controller endpoint " + endpoint + " is assigned more than once.");
                }
            }
            if (isBrokerRole(role)) brokerCount++;
            nodes.add(Map.of(
                    "hostId", String.valueOf(service.getHost_id()),
                    "address", address,
                    "nodeId", nodeId == null ? 0 : nodeId,
                    "role", role
            ));
        }

        if (controllerCount == 0) errors.add("At least one KRaft controller is required.");
        if (brokerCount == 0) errors.add("At least one KRaft broker is required.");
        if (controllerPort < 1 || controllerPort > 65535) errors.add("Controller port must be between 1 and 65535.");

        String clusterId = String.valueOf(config.getOrDefault("cluster_uuid", ""));
        if (!clusterId.matches("[A-Za-z0-9][A-Za-z0-9_-]{19,23}")) {
            errors.add("Kafka storage cluster ID is missing or invalid.");
        }

        String quorumMode = String.valueOf(config.getOrDefault("kraft_quorum_mode", "static"));
        String expectedVoters = buildQuorumVoters(request.getServices(), controllerPort);
        String expectedBootstrap = buildControllerBootstrapServers(request.getServices(), controllerPort);
        Object suppliedVoters = request.getConfig() == null ? null : request.getConfig().get("quorum_voters");
        if ("static".equals(quorumMode)) {
            if (!expectedVoters.equals(String.valueOf(config.getOrDefault("quorum_voters", "")))) {
                errors.add("controller.quorum.voters does not exactly match the selected controller IDs and endpoints.");
            }
            if (suppliedVoters != null && !String.valueOf(suppliedVoters).isBlank()
                    && !expectedVoters.equals(String.valueOf(suppliedVoters))) {
                errors.add("The supplied controller.quorum.voters conflicts with the selected topology.");
            }
        } else {
            if (suppliedVoters != null && !String.valueOf(suppliedVoters).isBlank()) {
                errors.add("Dynamic KRaft quorum must not configure controller.quorum.voters.");
            }
            if (!expectedBootstrap.equals(String.valueOf(config.getOrDefault("quorum_bootstrap_servers", "")))) {
                errors.add("controller.quorum.bootstrap.servers does not match the selected controllers.");
            }
            String initialControllers = String.valueOf(config.getOrDefault("initial_controllers", ""));
            if (initialControllers.split(",").length != controllerCount) {
                errors.add("Dynamic quorum initial controller membership is incomplete.");
            }
        }

        String environment = request.getEnvironment() == null ? "" : request.getEnvironment().trim().toUpperCase();
        boolean weakQuorum = controllerCount < 3;
        boolean evenQuorum = controllerCount > 1 && controllerCount % 2 == 0;
        if (weakQuorum) warnings.add(controllerCount + " controller quorum tolerates no controller failure; 3 controllers are recommended.");
        if (evenQuorum) warnings.add("Even controller count " + controllerCount + " provides no additional failure tolerance over " + (controllerCount - 1) + ".");
        if (controllerCount > 5) warnings.add("Controller count " + controllerCount + " is unusual; 3 or 5 controllers are recommended.");
        if ("UAT".equals(environment) && (weakQuorum || evenQuorum)) {
            errors.add("UAT requires an odd KRaft controller quorum with at least 3 controllers.");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("valid", errors.isEmpty());
        report.put("errors", errors.stream().distinct().toList());
        report.put("warnings", warnings.stream().distinct().toList());
        report.put("acknowledgementRequired", !warnings.isEmpty() && !"UAT".equals(environment));
        report.put("clusterId", clusterId);
        report.put("quorumMode", quorumMode);
        report.put("controllerCount", controllerCount);
        report.put("brokerCount", brokerCount);
        report.put("failureTolerance", controllerCount == 0 ? 0 : (controllerCount - 1) / 2);
        report.put("controllerQuorum", "static".equals(quorumMode) ? expectedVoters : expectedBootstrap);
        report.put("nodes", nodes);
        return report;
    }


    public String buildZooKeeperConnect(List<ServiceAssignmentReq> services, int zookeeperPort) {
        return services.stream()
                .filter(service -> isZooKeeperRole(service.getRole()))
                .map(service -> resolveHostAddress(service.getHost_id()) + ":" + zookeeperPort)
                .collect(Collectors.joining(","));
    }


    public String buildZooKeeperServers(List<ServiceAssignmentReq> services, int peerPort, int electionPort) {
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


    public String resolveHostAddress(String hostId) {
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


    public List<String> serviceRoleKinds(String role) {
        if ("broker_controller".equals(role)) {
            return List.of("broker", "controller");
        }
        if ("broker_zookeeper".equals(role)) {
            return List.of("broker", "zookeeper");
        }
        return List.of(role);
    }


    public String systemdServiceName(String role) {
        if ("controller".equals(role)) return "controller";
        if ("zookeeper".equals(role)) return "zookeeper";
        if ("broker_controller".equals(role)) return "kafka";
        if ("broker_zookeeper".equals(role)) return "kafka";
        return "broker";
    }


    public String configFileForRole(String role, String deploymentMode, String kafkaVersion, String installDir) {
        if ("zookeeper".equalsIgnoreCase(deploymentMode)) {
            if ("zookeeper".equals(role)) return installDir + "/config/zookeeper.properties";
            return installDir + "/config/server.properties";
        }
        String configRoot = parseKafkaVersion(kafkaVersion)[0] >= 4 ? installDir + "/config" : installDir + "/config/kraft";
        if ("controller".equals(role)) return configRoot + "/controller.properties";
        if ("broker".equals(role)) return configRoot + "/broker.properties";
        return configRoot + "/server.properties";
    }


    public String activeKafkaInstallDir(Map<String, Object> config) {
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


    public String defaultKafkaDataDir(Map<String, Object> config) {
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


    public String trimTrailingSlash(String value) {
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }


    public String brokerLogDirs(Map<String, Object> config, String dataDir) {
        Object configured = config.get("log_dirs");
        if (configured != null && !String.valueOf(configured).isBlank()) return String.valueOf(configured);
        return dataDir + "/broker-data";
    }


    public String metadataLogDirForRole(String role, Map<String, Object> config, String dataDir) {
        Object configured = config.get("metadata_log_dir");
        if (configured != null && !String.valueOf(configured).isBlank()) return String.valueOf(configured);
        if ("controller".equals(role)) return dataDir + "/controller-data/metadata";
        if (isControllerRole(role) && !isBrokerRole(role)) return dataDir + "/controller-data/metadata";
        return dataDir + "/broker-metadata";
    }


    public boolean isRoleAllowedForMode(String role, String deploymentMode) {
        if ("zookeeper".equals(deploymentMode)) {
            return "broker".equals(role) || "zookeeper".equals(role) || "broker_zookeeper".equals(role);
        }
        return "broker".equals(role) || "controller".equals(role) || "broker_controller".equals(role);
    }


    public boolean isBrokerRole(String role) {
        return "broker".equals(role) || "broker_controller".equals(role) || "broker_zookeeper".equals(role);
    }


    public boolean isControllerRole(String role) {
        return "controller".equals(role) || "broker_controller".equals(role);
    }


    public boolean isZooKeeperRole(String role) {
        return "zookeeper".equals(role) || "broker_zookeeper".equals(role);
    }


    public String normalizeDeploymentMode(String mode) {
        return "zookeeper".equalsIgnoreCase(mode) ? "zookeeper" : "kraft";
    }


    public boolean isZooKeeperSupported(String kafkaVersion) {
        int[] version = parseKafkaVersion(kafkaVersion);
        return version[0] < 4;
    }


    public int[] parseKafkaVersion(String kafkaVersion) {
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


    public int parseIntConfig(Object value, int defaultValue) {
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

}
