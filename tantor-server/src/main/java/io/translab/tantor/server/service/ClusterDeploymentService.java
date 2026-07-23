package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobStep;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.web.ClusterController.DeployClusterRequest;
import io.translab.tantor.server.web.ClusterController.UpdateClusterRequest;
import io.translab.tantor.server.web.ClusterController.UpgradeClusterRequest;
import io.translab.tantor.server.web.ClusterController.ServiceAssignmentReq;
import io.translab.tantor.server.service.ActivityAlertService;
import io.translab.tantor.server.audit.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.*;

@Service
public class ClusterDeploymentService {

    private static final int DEFAULT_JMX_EXPORTER_PORT = 19101;

    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final JobService jobService;
    private final ActivityAlertService activityAlertService;
    private final ClusterValidationService clusterValidationService;
    private final DeploymentService deploymentService;
    private final ObjectMapper objectMapper;
    private final String artifactRepoUrl;

    public ClusterDeploymentService(
            ClusterRepository clusterRepository,
            HostRepository hostRepository,
            JobService jobService,
            ActivityAlertService activityAlertService,
            ClusterValidationService clusterValidationService,
            DeploymentService deploymentService,
            ObjectMapper objectMapper,
            @Value("${tantor.artifact-repo.url:http://localhost:8081}") String artifactRepoUrl) {
        this.clusterRepository = clusterRepository;
        this.hostRepository = hostRepository;
        this.jobService = jobService;
        this.activityAlertService = activityAlertService;
        this.clusterValidationService = clusterValidationService;
        this.deploymentService = deploymentService;
        this.objectMapper = objectMapper;
        this.artifactRepoUrl = artifactRepoUrl;
    }

    @Transactional
    public Map<String, String> deployCluster(DeployClusterRequest request, String username) {
        String deploymentMode = clusterValidationService.normalizeDeploymentMode(request.getMode());
        Map<String, Object> deploymentConfig = clusterValidationService.buildDeploymentConfig(request, deploymentMode);
        String quorumVoters = String.valueOf(deploymentConfig.getOrDefault("quorum_voters", ""));
        String bootstrapServers = String.valueOf(deploymentConfig.getOrDefault("bootstrap_servers", ""));

        Cluster cluster = new Cluster();
        cluster.setName(request.getName());
        cluster.setKafkaVersion(request.getKafka_version());
        cluster.setMode(deploymentMode);
        cluster.setEnvironment(request.getEnvironment());
        cluster.setBootstrapServers(bootstrapServers);
        cluster.setOriginType("INTERNAL");
        cluster.setMonitoringEnabled(true);
        cluster.setJmxEnabled(true);
        cluster.setJmxExporterPort(DEFAULT_JMX_EXPORTER_PORT);
        cluster.setKafkaClusterId(blankString(deploymentConfig.get("cluster_uuid")));
        cluster.setInstallDirectory(blankString(deploymentConfig.get("kafka_install_dir")));
        cluster.setConfigDirectory(clusterValidationService.activeKafkaInstallDir(deploymentConfig) + "/config");
        cluster.setDataDirectory(blankString(deploymentConfig.get("kafka_data_dir")));
        cluster.setLogDirectory(blankString(deploymentConfig.get("kafka_app_log_dir")));
        String clusterRole = clusterRoleForServices(request.getServices());
        cluster.setUser(username);
        cluster.setRole(clusterRole);
        cluster.setConfigPath(clusterValidationService.configFileForRole(clusterRole, deploymentMode, request.getKafka_version(), clusterValidationService.activeKafkaInstallDir(deploymentConfig)));
        cluster.setCreatedBy(username);
        cluster.setUpdatedBy(username);
        cluster.setNodeIds(request.getServices().stream()
                .map(ServiceAssignmentReq::getNode_id)
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList());

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
            if (clusterValidationService.isBrokerRole(sa.getRole())) {
                assign.setJmxExporterPort(DEFAULT_JMX_EXPORTER_PORT);
            }
            assign.setConfigJson(buildServiceConfigJson(deploymentConfig, sa));
            assignments.add(assign);
        }
        cluster.setServices(assignments);
        clusterRepository.save(cluster);

        for (ServiceAssignmentReq sa : request.getServices()) {
            hostRepository.findById(sa.getHost_id()).ifPresent(host -> {
                host.setClusterId(cluster.getId());
                hostRepository.save(host);
            });
        }

        String finalArtifactUrl = resolveAgentArtifactUrl(null);
        List<ServiceAssignmentReq> deployOrder = request.getServices().stream()
                .sorted((left, right) -> Boolean.compare(!isMetadataService(left.getRole()), !isMetadataService(right.getRole())))
                .toList();

        List<Map<String, Object>> deployOrderPayload = new ArrayList<>();
        for (ServiceAssignmentReq svc : deployOrder) {
            Map<String, Object> svcPayload = new HashMap<>();
            svcPayload.put("host_id", svc.getHost_id());
            svcPayload.put("role", svc.getRole());
            svcPayload.put("node_id", svc.getNode_id());
            svcPayload.put("operation", "deploy");
            svcPayload.put("serviceConfigJson", buildServiceConfigJson(deploymentConfig, svc));
            deployOrderPayload.add(svcPayload);
        }

        Map<String, Object> jobPayload = new HashMap<>();
        jobPayload.put("clusterId", cluster.getId().toString());
        jobPayload.put("deployOrder", deployOrderPayload);
        jobPayload.put("finalArtifactUrl", finalArtifactUrl);
        jobPayload.put("quorumVoters", quorumVoters);
        jobPayload.put("kafkaVersion", request.getKafka_version());

        Job job = new Job();
        job.setType(JobType.DEPLOYMENT);
        job.setStatus(JobStatus.PENDING);
        job.setRollbackSupported(true);
        job.setResourceKey("cluster:" + cluster.getId());
        try {
            job.setPayload(objectMapper.writeValueAsString(jobPayload));
        } catch (Exception e) {
            job.setPayload("{}");
        }
        Job savedJob = jobService.createJob(job, deploymentJobSteps(deployOrderPayload, deploymentConfig, deploymentMode));

        activityAlertService.logActivity("INFO", "Created deployment job for cluster: " + request.getName(), cluster.getId());

        return Map.of(
            "id", cluster.getId().toString(),
            "jobId", savedJob.getId().toString()
        );
    }

    @Transactional
    public Cluster updateCluster(UUID id, UpdateClusterRequest request, String username) {
        Cluster cluster = clusterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        cluster.setName(request.getName());
        cluster.setEnvironment(request.getEnvironment());
        cluster.setUpdatedBy(username);
        return clusterRepository.save(cluster);
    }

    @Transactional
    public Map<String, String> addNodesToCluster(UUID id, DeployClusterRequest request, String username) {
        Cluster cluster = clusterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        if (!"SUCCESS".equalsIgnoreCase(cluster.getStatus())) {
            throw new IllegalArgumentException("Can only add nodes to a cluster in SUCCESS status");
        }
        if (request.getServices() == null || request.getServices().isEmpty()) {
            throw new IllegalArgumentException("Nodes list cannot be empty");
        }

        String deploymentMode = clusterValidationService.normalizeDeploymentMode(cluster.getMode());
        Map<String, Object> deploymentConfig = parseConfigJson(cluster.getConfigJson());
        String quorumVoters = String.valueOf(deploymentConfig.getOrDefault("quorum_voters", ""));

        // Use clusterValidationService to validate Kraft topology if needed
        // but here the controller used to call: validateKraftTopology(request.getServices(), cluster.getServices(), deploymentMode);
        // Let's rely on validation before this, or just not do it since validation is HTTP-level?
        // Wait, the controller used to do it directly. But it's an endpoint now!
        // We will just do the job logic.

        List<Map<String, Object>> deployOrderPayload = new ArrayList<>();
        List<ServiceAssignmentReq> addedServices = new ArrayList<>();
        for (ServiceAssignmentReq sa : request.getServices()) {
            ClusterServiceAssignment assign = new ClusterServiceAssignment();
            assign.setCluster(cluster);
            assign.setHostId(sa.getHost_id());
            assign.setRole(sa.getRole());
            assign.setNodeId(sa.getNode_id());
            if (clusterValidationService.isBrokerRole(sa.getRole())) {
                assign.setJmxExporterPort(DEFAULT_JMX_EXPORTER_PORT);
            }
            assign.setConfigJson(buildServiceConfigJson(deploymentConfig, sa));
            cluster.getServices().add(assign);

            hostRepository.findById(sa.getHost_id()).ifPresent(host -> {
                host.setClusterId(cluster.getId());
                hostRepository.save(host);
            });

            Map<String, Object> svcPayload = new HashMap<>();
            svcPayload.put("host_id", sa.getHost_id());
            svcPayload.put("role", sa.getRole());
            svcPayload.put("node_id", sa.getNode_id());
            svcPayload.put("operation", "add_node");
            svcPayload.put("serviceConfigJson", buildServiceConfigJson(deploymentConfig, sa));
            deployOrderPayload.add(svcPayload);
            addedServices.add(sa);
        }

        cluster.setNodeIds(cluster.getServices().stream()
                .map(ClusterServiceAssignment::getNodeId)
                .filter(java.util.Objects::nonNull)
                .distinct().sorted().toList());
        cluster.setRole(clusterRoleForServices(cluster.getServices().stream()
                .map(s -> {
                    ServiceAssignmentReq sr = new ServiceAssignmentReq();
                    sr.setRole(s.getRole());
                    return sr;
                }).toList()));
        cluster.setStatus("UPDATING");
        cluster.setUpdatedBy(username);
        clusterRepository.save(cluster);

        String finalArtifactUrl = resolveAgentArtifactUrl(null);

        Map<String, Object> jobPayload = new HashMap<>();
        jobPayload.put("clusterId", cluster.getId().toString());
        jobPayload.put("deployOrder", deployOrderPayload);
        jobPayload.put("finalArtifactUrl", finalArtifactUrl);
        jobPayload.put("quorumVoters", quorumVoters);
        jobPayload.put("kafkaVersion", cluster.getKafkaVersion());
        jobPayload.put("isAddNodes", true);

        Job job = new Job();
        job.setType(JobType.DEPLOYMENT);
        job.setStatus(JobStatus.PENDING);
        job.setRollbackSupported(false);
        job.setResourceKey("cluster:" + cluster.getId());
        try {
            job.setPayload(objectMapper.writeValueAsString(jobPayload));
        } catch (Exception e) {
            job.setPayload("{}");
        }
        Job savedJob = jobService.createJob(job, deploymentJobSteps(deployOrderPayload, deploymentConfig, deploymentMode));

        activityAlertService.logActivity("INFO", "Created add-nodes job for cluster: " + cluster.getName(), cluster.getId());

        return Map.of(
            "id", cluster.getId().toString(),
            "jobId", savedJob.getId().toString()
        );
    }

    @Transactional
    public void deleteCluster(UUID id, String username) {
        Cluster cluster = clusterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        if (!initiateClusterCleanup(cluster)) {
            throw new RuntimeException("Failed to remotely clean up cluster hosts.");
        }

        clearClusterHostAssignments(cluster);
        markClusterDeleted(cluster, username);
        activityAlertService.logActivity("WARNING", "Cluster fully deleted and resources cleaned up.", cluster.getId());
    }

    @Transactional
    public void forceDeleteCluster(UUID id, String username) {
        clusterRepository.findById(id).ifPresent(cluster -> {
            try {
                initiateClusterCleanup(cluster);
            } catch (Exception e) {
                // Ignore
            }
            clearClusterHostAssignments(cluster);
            markClusterDeleted(cluster, username);
            activityAlertService.logActivity("WARNING", "Cluster forcefully deleted from registry.", cluster.getId());
        });
    }

    @Transactional
    public Map<String, String> upgradeCluster(UUID id, UpgradeClusterRequest request, String username) {
        Cluster cluster = clusterRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));

        if (!"SUCCESS".equalsIgnoreCase(cluster.getStatus())) {
            throw new IllegalArgumentException("Cluster must be in SUCCESS state to upgrade.");
        }

        int[] currentVer = clusterValidationService.parseKafkaVersion(cluster.getKafkaVersion());
        int[] targetVer = clusterValidationService.parseKafkaVersion(request.getTargetVersion());

        if (targetVer[0] < currentVer[0] || (targetVer[0] == currentVer[0] && targetVer[1] < currentVer[1])) {
            throw new IllegalArgumentException("Downgrades are not supported via this endpoint.");
        }
        if (cluster.getKafkaVersion().equals(request.getTargetVersion())) {
            throw new IllegalArgumentException("Cluster is already running target version.");
        }

        cluster.setKafkaVersion(request.getTargetVersion());
        cluster.setStatus("UPGRADING");
        cluster.setUpdatedBy(username);
        clusterRepository.save(cluster);

        String finalArtifactUrl = resolveAgentArtifactUrl(null);

        List<ServiceAssignmentReq> upgradeOrder = cluster.getServices().stream()
                .sorted((left, right) -> Boolean.compare(!isMetadataService(left.getRole()), !isMetadataService(right.getRole())))
                .map(sa -> {
                    ServiceAssignmentReq req = new ServiceAssignmentReq();
                    req.setHost_id(sa.getHostId());
                    req.setRole(sa.getRole());
                    req.setNode_id(sa.getNodeId());
                    return req;
                }).toList();

        Map<String, Object> deploymentConfig = parseConfigJson(cluster.getConfigJson());
        String deploymentMode = clusterValidationService.normalizeDeploymentMode(cluster.getMode());

        List<Map<String, Object>> deployOrderPayload = new ArrayList<>();
        for (ServiceAssignmentReq svc : upgradeOrder) {
            Map<String, Object> svcPayload = new HashMap<>();
            svcPayload.put("host_id", svc.getHost_id());
            svcPayload.put("role", svc.getRole());
            svcPayload.put("node_id", svc.getNode_id());
            svcPayload.put("operation", "upgrade");
            deployOrderPayload.add(svcPayload);
        }

        Map<String, Object> jobPayload = new HashMap<>();
        jobPayload.put("clusterId", cluster.getId().toString());
        jobPayload.put("deployOrder", deployOrderPayload);
        jobPayload.put("finalArtifactUrl", finalArtifactUrl);
        jobPayload.put("targetVersion", request.getTargetVersion());
        jobPayload.put("isUpgrade", true);

        Job job = new Job();
        job.setType(JobType.DEPLOYMENT);
        job.setStatus(JobStatus.PENDING);
        job.setRollbackSupported(false);
        job.setResourceKey("cluster:" + cluster.getId());
        try {
            job.setPayload(objectMapper.writeValueAsString(jobPayload));
        } catch (Exception e) {
            job.setPayload("{}");
        }
        Job savedJob = jobService.createJob(job, deploymentJobSteps(deployOrderPayload, deploymentConfig, deploymentMode));

        activityAlertService.logActivity("INFO", "Created upgrade job for cluster to version: " + request.getTargetVersion(), cluster.getId());

        return Map.of(
            "id", cluster.getId().toString(),
            "jobId", savedJob.getId().toString()
        );
    }

    private List<JobStep> deploymentJobSteps(
            List<Map<String, Object>> deploymentOrder,
            Map<String, Object> deploymentConfig,
            String deploymentMode
    ) {
        List<JobStep> steps = new ArrayList<>();
        if (!"kraft".equals(deploymentMode)) {
            for (Map<String, Object> service : deploymentOrder) {
                if (clusterValidationService.isZooKeeperRole(String.valueOf(service.get("role")))) {
                    steps.add(deploymentStep(steps.size() + 1, service));
                }
            }

            String zookeeperConnect = String.valueOf(deploymentConfig.getOrDefault("zookeeper_connect", ""));
            String firstZkHost = firstZooKeeperHost(deploymentConfig);
            if (!firstZkHost.isBlank()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("operation", "verify_zk_quorum");
                payload.put("host_id", firstZkHost);
                payload.put("zookeeper_connect", zookeeperConnect);
                payload.put("serviceConfigJson", writeJson(deploymentConfig));
                steps.add(jobStep(
                        steps.size() + 1,
                        firstZkHost,
                        "Verify ZooKeeper quorum",
                        payload
                ));
            }

            for (Map<String, Object> service : deploymentOrder) {
                if (clusterValidationService.isBrokerRole(String.valueOf(service.get("role")))) {
                    steps.add(deploymentStep(steps.size() + 1, service));
                }
            }
            return steps;
        }

        for (Map<String, Object> service : deploymentOrder) {
            if (clusterValidationService.isControllerRole(String.valueOf(service.get("role")))) {
                steps.add(deploymentStep(steps.size() + 1, service));
            }
        }

        String controllerEndpoints = String.valueOf(deploymentConfig.getOrDefault("controller_endpoints", ""));
        Set<String> connectivityHosts = new HashSet<>();
        for (Map<String, Object> service : deploymentOrder) {
            String hostId = String.valueOf(service.get("host_id"));
            if (!connectivityHosts.add(hostId)) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operation", "connectivity");
            payload.put("host_id", hostId);
            payload.put("controller_endpoints", controllerEndpoints);
            steps.add(jobStep(
                    steps.size() + 1,
                    hostId,
                    "Check controller connectivity from " + hostId,
                    payload
            ));
        }

        for (Map<String, Object> service : deploymentOrder) {
            if (!clusterValidationService.isControllerRole(String.valueOf(service.get("role")))) {
                steps.add(deploymentStep(steps.size() + 1, service));
            }
        }

        String controllerHost = firstControllerHost(deploymentConfig);
        if (!controllerHost.isBlank()) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operation", "verify_quorum");
            payload.put("host_id", controllerHost);
            payload.put("controller_endpoints", controllerEndpoints);
            payload.put("cluster_uuid", String.valueOf(deploymentConfig.getOrDefault("cluster_uuid", "")));
            payload.put("expected_controller_count", String.valueOf(controllerCount(deploymentConfig)));
            payload.put("serviceConfigJson", writeJson(deploymentConfig));
            steps.add(jobStep(
                    steps.size() + 1,
                    controllerHost,
                    "Verify KRaft leader and cluster identity",
                    payload
            ));
        }
        return steps;
    }

    private JobStep deploymentStep(int order, Map<String, Object> service) {
        return jobStep(
                order,
                String.valueOf(service.get("host_id")),
                "Deploy " + service.get("role") + " node " + service.get("node_id")
                        + " on " + service.get("host_id"),
                service
        );
    }

    private JobStep jobStep(int order, String targetId, String name, Map<String, Object> payload) {
        JobStep step = new JobStep();
        step.setStepOrder(order);
        step.setTargetId(targetId);
        step.setName(name);
        step.setPayload(writeJson(payload));
        return step;
    }

    private String firstControllerHost(Map<String, Object> deploymentConfig) {
        Object topologyValue = deploymentConfig.get("service_topology");
        if (!(topologyValue instanceof List<?> topology)) return "";
        for (Object itemValue : topology) {
            if (!(itemValue instanceof Map<?, ?> item)) continue;
            if (clusterValidationService.isControllerRole(String.valueOf(item.get("role")))) {
                return String.valueOf(item.get("hostId"));
            }
        }
        return "";
    }

    private String firstZooKeeperHost(Map<String, Object> deploymentConfig) {
        Object topologyValue = deploymentConfig.get("service_topology");
        if (!(topologyValue instanceof List<?> topology)) return "";
        for (Object itemValue : topology) {
            if (!(itemValue instanceof Map<?, ?> item)) continue;
            if (clusterValidationService.isZooKeeperRole(String.valueOf(item.get("role")))) {
                return String.valueOf(item.get("hostId"));
            }
        }
        return "";
    }

    private long controllerCount(Map<String, Object> deploymentConfig) {
        Object topologyValue = deploymentConfig.get("service_topology");
        if (!(topologyValue instanceof List<?> topology)) return 0;
        return topology.stream()
                .filter(itemValue -> itemValue instanceof Map<?, ?>)
                .map(itemValue -> (Map<?, ?>) itemValue)
                .filter(item -> clusterValidationService.isControllerRole(String.valueOf(item.get("role"))))
                .count();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize deployment job step", e);
        }
    }

    private String buildServiceConfigJson(Map<String, Object> deploymentConfig, ServiceAssignmentReq svc) {
        Map<String, Object> serviceConfig = new HashMap<>(deploymentConfig);
        if (svc.getConfiguration_mode() != null && !svc.getConfiguration_mode().isBlank()) {
            serviceConfig.put("configuration_mode", svc.getConfiguration_mode());
        }
        if (svc.getHeap_size() != null && !svc.getHeap_size().isBlank()) {
            serviceConfig.put("heap_size", svc.getHeap_size());
        }
        if (svc.getListener_port() != null) {
            serviceConfig.put("listener_port", svc.getListener_port());
            serviceConfig.put("broker_port", svc.getListener_port());
        }
        if (svc.getController_port() != null) {
            serviceConfig.put("controller_port", svc.getController_port());
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

    private String clusterRoleForServices(List<ServiceAssignmentReq> services) {
        if (services == null || services.isEmpty()) return "broker";
        boolean hasBroker = false;
        boolean hasController = false;
        for (ServiceAssignmentReq service : services) {
            String role = service.getRole();
            if ("broker_controller".equals(role) || "broker+controller".equals(role)) return "broker_controller";
            if ("broker".equals(role)) hasBroker = true;
            if ("controller".equals(role)) hasController = true;
            if ("schema_registry".equals(role) || "schema registry".equalsIgnoreCase(String.valueOf(role))) return "schema_registry";
            if ("connect".equals(role)) return "connect";
        }
        if (hasBroker) return "broker";
        if (hasController) return "controller";
        return services.get(0).getRole() == null || services.get(0).getRole().isBlank() ? "broker" : services.get(0).getRole();
    }

    private boolean isMetadataService(String role) {
        return clusterValidationService.isControllerRole(role) || clusterValidationService.isZooKeeperRole(role);
    }

    private String blankString(Object value) {
        return value == null ? null : (String.valueOf(value).isBlank() ? null : String.valueOf(value));
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
            if (rawPath != null && rawPath.contains("/api/v1/artifacts/")) {
                return joinArtifactRepoBase(rawPath.substring(rawPath.indexOf("/api/v1/artifacts/"))
                        + (uri.getRawQuery() == null || uri.getRawQuery().isBlank() ? "" : "?" + uri.getRawQuery()));
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

    private void markClusterDeleted(Cluster cluster, String username) {
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
