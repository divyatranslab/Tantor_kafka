package io.translab.tantor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ClusterServiceAssignmentRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.DiscoveryAgent;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.scheduling.annotation.Scheduled;
import io.translab.tantor.server.audit.AuditService;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.translab.tantor.server.repository.ExternalClusterRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalClusterService {

    private static final String EXTERNAL_MODE = "EXTERNAL";
    private static final long AGENT_STALE_SECONDS = 90;

    private final ClusterRepository clusterRepository;
    private final ExternalClusterRepository externalClusterRepository;
    private final io.translab.tantor.server.repository.ExternalClusterNodeRepository externalClusterNodeRepository;
    private final ClusterServiceAssignmentRepository clusterServiceAssignmentRepository;
    private final HostRepository hostRepository;
    private final DiscoveryAgentRepository discoveryAgentRepository;
    private final KafkaAdminService kafkaAdminService;
    private final ObjectMapper objectMapper;
    private final ActivityAlertService activityAlertService;
    private final AuditService auditService;

    private final Map<String, ExternalAgentTask> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, ExternalDiscoveryReport> pendingDiscoveries = new ConcurrentHashMap<>();

    public Map<String, Object> testBootstrap(String bootstrapServers) {
        String query = bootstrapServers.trim();
        String queryHost = extractHostFromBootstrap(query);

        Map<String, Object> result = new LinkedHashMap<>();
        boolean adminSuccess = false;
        
        try {
            // 1. PRIMARY: Try connecting directly via Kafka Admin API FIRST
            Map<String, Object> adminData = kafkaAdminService.inspectBootstrapServers(query);
            result.putAll(adminData);
            adminSuccess = true;
        } catch (Exception e) {
            // It failed. Don't throw yet, check if agent is enrolled as fallback.
            result.put("connected", false);
            result.put("message", e.getMessage());
        }

        // 2. Check if a Discovery Agent has already reported this cluster
        boolean agentFound = false;
        for (Map.Entry<String, ExternalDiscoveryReport> entry : pendingDiscoveries.entrySet()) {
            ExternalDiscoveryReport report = entry.getValue();
            boolean matchBootstrap = report.getBootstrapServers() != null && report.getBootstrapServers().contains(query);
            boolean matchHostname = report.getHostname() != null && report.getHostname().equalsIgnoreCase(queryHost);
            boolean matchIp = report.getIpAddresses() != null && report.getIpAddresses().contains(queryHost);
            
            if (matchBootstrap || matchHostname || matchIp) {
                agentFound = true;
                result.put("agentFound", true);
                result.put("discoveryKey", entry.getKey());
                result.put("agentType", "KAFKA_DISCOVERY");
                
                // If AdminClient failed, do NOT force success. We just pass the agent data as extra context
                // but leave connected = false so the UI knows the direct connection failed.
                if (!adminSuccess) {
                    result.put("clusterId", report.getKafkaClusterId());
                    result.put("cluster_id", report.getKafkaClusterId());
                    result.put("kafkaVersion", blankToDefault(report.getKafkaVersion(), "Unknown"));
                    result.put("kafka_version", blankToDefault(report.getKafkaVersion(), "Unknown"));
                    result.put("kafkaMode", blankToDefault(report.getKafkaMode(), "Unknown"));
                    result.put("mode", blankToDefault(report.getKafkaMode(), "Unknown"));
                    // Do NOT override brokerCount or security_protocol from the agent here
                    // because the test failed, meaning the listener the user typed is invalid or unreachable.
                    // If we blindly copied the agent's SASL_SSL it would confuse them.
                    result.put("message", result.getOrDefault("message", "Direct Admin API connection failed. Agent is enrolled, but the bootstrap port is unreachable or invalid."));
                } else {
                    // Just add the agent message to the successful admin data
                    result.put("message", "Direct Connection successful. Discovery Agent also enrolled.");
                }
                break;
            }
        }
        
        if (!agentFound) {
            result.put("agentFound", false);
            // If admin also failed and no agent found, rethrow to trigger the UI error properly
            if (!adminSuccess) {
                throw new IllegalArgumentException(String.valueOf(result.getOrDefault("message", "Admin connection failed and no agent enrolled.")));
            }
        }

        // 3. Inject node-level agent availability
        if (adminSuccess && result.get("brokers") != null) {
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("brokers");
            OffsetDateTime now = OffsetDateTime.now();
            List<DiscoveryAgent> dbAgents = discoveryAgentRepository.findAll();
            
            for (Map<String, Object> node : nodes) {
                String host = String.valueOf(node.get("host"));
                boolean nodeAgentFound = false;
                
                // Check pending discoveries first
                for (Map.Entry<String, ExternalDiscoveryReport> entry : pendingDiscoveries.entrySet()) {
                    ExternalDiscoveryReport report = entry.getValue();
                    boolean match = (report.getHostname() != null && report.getHostname().equalsIgnoreCase(host)) || 
                                    (report.getIpAddresses() != null && report.getIpAddresses().contains(host));
                    if (match && report.isRunning()) {
                        try {
                            OffsetDateTime lastSeen = OffsetDateTime.parse(report.getLastSeen());
                            if (java.time.Duration.between(lastSeen, now).getSeconds() <= 120) {
                                node.put("hasActiveAgent", true);
                                node.put("agentDiscoveryKey", entry.getKey());
                                nodeAgentFound = true;
                                break;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                
                // If not in pending, check DB
                if (!nodeAgentFound) {
                    for (DiscoveryAgent agent : dbAgents) {
                        boolean match = (agent.getHostname() != null && agent.getHostname().equalsIgnoreCase(host)) ||
                                        (agent.getIpAddresses() != null && agent.getIpAddresses().contains(host));
                        if (match && "ONLINE".equalsIgnoreCase(agent.getStatus()) && agent.getLastHeartbeat() != null) {
                            if (java.time.Duration.between(agent.getLastHeartbeat(), now).getSeconds() <= 120) {
                                node.put("hasActiveAgent", true);
                                node.put("agentDiscoveryKey", agent.getId());
                                nodeAgentFound = true;
                                break;
                            }
                        }
                    }
                }
                
                if (!nodeAgentFound) {
                    node.put("hasActiveAgent", false);
                }
            }
        }

        return result;
    }

    @Transactional
    public ExternalCluster registerBootstrapCluster(BootstrapExternalClusterRequest request) {
        if (request.getBootstrapServers() == null || request.getBootstrapServers().isBlank()) {
            throw new IllegalArgumentException("Bootstrap servers are required.");
        }

        String bootstrap = request.getBootstrapServers().trim();
        Map<String, Object> inspection;
        
        if (request.getClusterId() != null && !request.getClusterId().isBlank()) {
            inspection = new HashMap<>();
            inspection.put("connected", true);
            inspection.put("clusterId", request.getClusterId());
            inspection.put("brokerCount", request.getBrokerCount());
            inspection.put("security_protocol", request.getSecurity());
            inspection.put("agentFound", request.getAgentFound());
            inspection.put("discoveryKey", request.getDiscoveryKey());
            inspection.put("kafka_version", request.getKafkaVersion());
            inspection.put("mode", request.getKafkaMode());
            inspection.put("controllerId", request.getControllerId());
            inspection.put("brokers", request.getBrokers());
        } else {
            inspection = testBootstrap(bootstrap);
        }
        
        // Ensure Kafka Admin API was able to connect
        if (!Boolean.TRUE.equals(inspection.get("connected"))) {
            throw new IllegalArgumentException(String.valueOf(inspection.getOrDefault("message", "Bootstrap connection failed.")));
        }

        ExternalCluster savedCluster = null;

        // Create the ExternalCluster entity based on AdminClient data (source of truth)
        String clusterId = String.valueOf(inspection.get("clusterId"));
        savedCluster = findExternalCluster(clusterId, request.getName(), bootstrap).orElseGet(ExternalCluster::new);
        savedCluster.setName(request.getName() != null ? request.getName().trim() : savedCluster.getName());
        savedCluster.setBootstrapServers(mergeBootstrapServers(savedCluster.getBootstrapServers(), bootstrap));
        savedCluster.setKafkaClusterId(clusterId);
        savedCluster.setKafkaVersion(String.valueOf(inspection.get("kafkaVersion")));
        savedCluster.setEnvironment(blankToDefault(request.getEnvironment(), "unknown"));
        savedCluster.setKafkaMode(String.valueOf(inspection.get("mode")));
        savedCluster.setSecurity(String.valueOf(inspection.get("security_protocol")));
        if (inspection.get("brokerCount") instanceof Number) {
            savedCluster.setBrokerCount(((Number) inspection.get("brokerCount")).intValue());
        }
        savedCluster.setStatus("SUCCESS");
        savedCluster = externalClusterRepository.save(savedCluster);

        // Process Selected Agents
        if (request.getSelectedAgents() != null && !request.getSelectedAgents().isEmpty()) {
            for (Map.Entry<String, String> entry : request.getSelectedAgents().entrySet()) {
                String agentId = entry.getValue();
                // Check if it's a pending discovery
                if (pendingDiscoveries.containsKey(agentId)) {
                    ExternalDiscoveryReport report = pendingDiscoveries.get(agentId);
                    upsertDiscoveryAgent(report, savedCluster);
                    pendingDiscoveries.remove(agentId);
                } else {
                    // Update existing db agent to link to this cluster
                    Optional<DiscoveryAgent> optAgent = discoveryAgentRepository.findById(agentId);
                    if (optAgent.isPresent()) {
                        DiscoveryAgent agent = optAgent.get();
                        agent.setClusterId(savedCluster.getId());
                        discoveryAgentRepository.save(agent);
                    }
                }
            }
        }

        // Map brokers to externalClusterNodeRepository (full topology)
        List<Map<String, Object>> brokers = (List<Map<String, Object>>) inspection.get("brokers");
        if (brokers != null) {
            for (Map<String, Object> b : brokers) {
                String idStr = String.valueOf(b.get("id"));
                Integer nodeId = null;
                if (b.get("id") != null) {
                    try { nodeId = Integer.parseInt(idStr); } catch (NumberFormatException ignored) {}
                }
                String host = String.valueOf(b.get("host"));
                boolean isController = Boolean.TRUE.equals(b.get("isController"));
                boolean isBroker = Boolean.TRUE.equals(b.get("isBroker"));
                
                Integer port = null;
                if (b.get("port") != null) {
                    try { port = Integer.parseInt(String.valueOf(b.get("port"))); } catch (NumberFormatException ignored) {}
                }
                
                externalClusterNodeRepository.upsertTopology(
                        savedCluster.getId(),
                        host,
                        nodeId,
                        isBroker,
                        isController,
                        port
                );
            }
        }
        
        return savedCluster;
    }

    @Transactional
    public Map<String, Object> recordDiscoveryReport(ExternalDiscoveryReport report) {
        validateDiscoveryReport(report);
        report.setLastSeen(OffsetDateTime.now().toString());
        upsertDiscoveryAgent(report, null);

        String agentId = report.getHostId() == null || report.getHostId().isBlank() 
                ? discoveryHostId(report) 
                : report.getHostId();
        io.translab.tantor.server.domain.DiscoveryAgent agent = discoveryAgentRepository.findById(agentId).orElse(null);

        Optional<ExternalCluster> connectedCluster = findExternalCluster(report.getKafkaClusterId(), report.getName(), report.getBootstrapServers().trim());
        
        if (connectedCluster.isPresent() && agent != null && connectedCluster.get().getId().equals(agent.getClusterId())) {
            ExternalCluster cluster = upsertDiscoveryCluster(report);
            
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(cluster.getId());
            for (io.translab.tantor.server.domain.ExternalClusterNode node : nodes) {
                boolean match = (agent.getHostname() != null && agent.getHostname().equalsIgnoreCase(node.getHost())) ||
                                (agent.getIpAddresses() != null && agent.getIpAddresses().contains(node.getHost()));
                if (match) {
                    node.setCpuUsagePct(report.getCpuUsagePct());
                    node.setMemoryUsedMb(report.getMemoryUsedMb());
                    node.setMemoryTotalMb(report.getMemoryTotalMb());
                    node.setDiskUsedGb(report.getDiskUsedGb());
                    node.setDiskTotalGb(report.getDiskTotalGb());
                    node.setLastSeen(OffsetDateTime.now());
                    
                    boolean nodeIdMatches = (report.getNodeId() != null && report.getNodeId().equals(node.getNodeId())) ||
                                            (report.getBrokerCount() > 0 && report.getNodeId() != null && report.getNodeId().equals(node.getNodeId())); // Fallback if brokerId is not sent separately
                    
                    // Always try to match by nodeId. (brokerId == nodeId in most cases).
                    if (report.getNodeId() != null && report.getNodeId().equals(node.getNodeId())) {
                        node.setInstallDir(report.getInstallPath());
                        node.setLogDirs(report.getLogDirs());
                        node.setConfigFile(report.getConfigFile());
                        node.setDataDirs(report.getDataDirs());
                    }
                    externalClusterNodeRepository.save(node);
                }
            }
            
            return Map.of(
                    "id", cluster.getId(),
                    "name", cluster.getName(),
                    "status", "registered",
                    "managementLevel", "AGENT_MANAGED"
            );
        }

        String key = discoveryKey(report);
        pendingDiscoveries.put(key, report);
        return Map.of(
                "discoveryKey", key,
                "name", report.getName(),
                "status", "pending"
        );
    }

    public List<Map<String, Object>> listPendingDiscoveries() {
        return pendingDiscoveries.entrySet().stream()
                .filter(entry -> findExternalCluster(
                        entry.getValue().getKafkaClusterId(),
                        entry.getValue().getName(),
                        entry.getValue().getBootstrapServers()
                ).isEmpty())
                .filter(entry -> entry.getValue().isRunning())
                .filter(entry -> isFreshDiscovery(entry.getValue()))
                .sorted(Map.Entry.comparingByValue(Comparator.comparing(
                        ExternalDiscoveryReport::getLastSeen,
                        Comparator.nullsLast(Comparator.reverseOrder())
                )))
                .map(entry -> toDiscoverySummary(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Map<String, Object> inspectDiscovery(String discoveryKey) {
        ExternalDiscoveryReport report = requiredPendingDiscovery(discoveryKey);
        Map<String, Object> inspection = new LinkedHashMap<>();
        
        inspection.put("connected", true);
        inspection.put("success", true);
        inspection.put("status", "CONNECTED");

        pendingDiscoveries.put(discoveryKey, report);
        inspection.put("discoveryKey", discoveryKey);
        inspection.put("name", report.getName());
        inspection.put("hostname", report.getHostname());
        inspection.put("bootstrapServers", report.getBootstrapServers());
        inspection.put("bootstrap_servers", report.getBootstrapServers());
        inspection.put("kafkaVersion", blankToDefault(report.getKafkaVersion(), "Unknown"));
        inspection.put("kafka_version", blankToDefault(report.getKafkaVersion(), "Unknown"));
        inspection.put("kafkaMode", blankToDefault(report.getKafkaMode(), "Unknown"));
        inspection.put("mode", blankToDefault(report.getKafkaMode(), "Unknown"));
        inspection.put("kafkaClusterId", blankToDefault(report.getKafkaClusterId(), ""));
        inspection.put("kafka_cluster_id", blankToDefault(report.getKafkaClusterId(), ""));
        inspection.put("security", blankToDefault(report.getSecurity(), "PLAINTEXT"));
        inspection.put("environment", blankToDefault(report.getEnvironment(), "unknown"));
        inspection.put("installPath", blankToDefault(report.getInstallPath(), ""));
        inspection.put("logDirs", blankToDefault(report.getLogDirs(), ""));
        inspection.put("nodeId", report.getNodeId());
        inspection.put("lastSeen", report.getLastSeen());
        inspection.put("agentType", "KAFKA_DISCOVERY");
        return inspection;
    }

    @Transactional
    public ExternalCluster connectDiscovery(String discoveryKey) {
        Map<String, Object> inspection = inspectDiscovery(discoveryKey);
        if (!Boolean.TRUE.equals(inspection.get("connected"))) {
            throw new IllegalArgumentException(String.valueOf(inspection.getOrDefault(
                    "message",
                    "The discovered Kafka bootstrap server is not reachable."
            )));
        }

        ExternalDiscoveryReport report = requiredPendingDiscovery(discoveryKey);
        ExternalCluster cluster = upsertDiscoveryCluster(report);
        pendingDiscoveries.remove(discoveryKey);
        return cluster;
    }

    @Transactional
    public ExternalCluster upsertDiscoveryCluster(ExternalDiscoveryReport report) {
        validateDiscoveryReport(report);

        String bootstrap = report.getBootstrapServers().trim();
        ExternalCluster cluster = findExternalCluster(report.getKafkaClusterId(), report.getName(), bootstrap)
                .orElseGet(ExternalCluster::new);

        return saveDiscoveryCluster(report, bootstrap, cluster);
    }

    private void validateDiscoveryReport(ExternalDiscoveryReport report) {
        if (report.getName() == null || report.getName().isBlank()) {
            throw new IllegalArgumentException("Discovered cluster name is required.");
        }
        if (report.getBootstrapServers() == null || report.getBootstrapServers().isBlank()) {
            throw new IllegalArgumentException("Discovered bootstrap servers are required.");
        }
    }

    private ExternalCluster saveDiscoveryCluster(ExternalDiscoveryReport report, String bootstrap, ExternalCluster cluster) {
        cluster.setName(cluster.getId() == null ? report.getName().trim() : cluster.getName());
        cluster.setBootstrapServers(bootstrap);
        cluster.setKafkaClusterId(blankToDefault(report.getKafkaClusterId(), null));
        cluster.setInstallPath(blankToDefault(report.getInstallPath(), null));
        cluster.setLogDirs(blankToDefault(report.getLogDirs(), null));
        cluster.setKafkaVersion(blankToDefault(report.getKafkaVersion(), "Unknown"));
        cluster.setEnvironment(blankToDefault(report.getEnvironment(), "unknown"));
        cluster.setBootstrapServers(mergeBootstrapServers(cluster.getBootstrapServers(), bootstrap));
        cluster.setStatus(report.isRunning() ? "SUCCESS" : "DEGRADED");
        cluster.setKafkaMode(blankToDefault(report.getKafkaMode(), "KRaft"));
        cluster.setSecurity(blankToDefault(report.getSecurity(), "PLAINTEXT"));
        cluster.setBrokerCount(report.getBrokerCount());
        cluster.setListeners(report.getListeners());
        cluster.setAdvertisedListeners(report.getAdvertisedListeners());
        cluster.setProcessRoles(report.getProcessRoles());
        cluster.setCpuUsagePct(report.getCpuUsagePct());
        cluster.setMemoryUsedMb(report.getMemoryUsedMb());
        cluster.setMemoryTotalMb(report.getMemoryTotalMb());
        cluster.setDiskUsedGb(report.getDiskUsedGb());
        cluster.setDiskTotalGb(report.getDiskTotalGb());
        cluster.setIsRunning(report.isRunning());

        ExternalCluster saved = externalClusterRepository.save(cluster);
        
        report.setLastSeen(OffsetDateTime.now().toString());

        activityAlertService.logActivity("INFO", "Discovered external cluster via agent: " + saved.getName(), saved.getId());
        
        auditService.record(
            "CLUSTER_MANAGEMENT",
            "EXTERNAL_CLUSTER_CONNECTED",
            "CLUSTER",
            saved.getId().toString(),
            saved.getId(),
            "SUCCESS",
            null,
            null,
            null,
            Map.of(
                "message", "Successfully connected to external cluster " + saved.getName(),
                "kafkaClusterId", String.valueOf(saved.getKafkaClusterId()),
                "brokerCount", report.getBrokerCount(),
                "nodes", writeJson(report)
            )
        );
        
        return saved;
    }

    @Transactional
    public void receiveMetrics(String clusterName, ExternalBrokerMetricsDto metrics) {
        Optional<ExternalCluster> clusterOpt = externalClusterRepository.findByNameAndStatusNot(clusterName, "DELETED");
        if (clusterOpt.isEmpty()) {
            return;
        }

        ExternalCluster cluster = clusterOpt.get();
        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster);
        String bootstrap = blankToDefault(metrics.getBootstrap(), cluster.getBootstrapServers());
        ExternalBrokerRecord broker = brokers.stream()
                .filter(item -> safeEquals(item.getHostname(), metrics.getHostname()) 
                        || (item.getBootstrap() != null && bootstrap != null && (item.getBootstrap().contains(bootstrap) || bootstrap.contains(item.getBootstrap())))
                        || (item.getHostname() != null && bootstrap != null && bootstrap.contains(item.getHostname())))
                .findFirst()
                .orElseGet(() -> {
                    ExternalBrokerRecord item = new ExternalBrokerRecord();
                    item.setHostname(metrics.getHostname());
                    item.setBootstrap(bootstrap);
                    item.setRole("broker");
                    brokers.add(item);
                    return item;
                });

        broker.setCpuUsagePct(metrics.getCpuUsagePct());
        broker.setMemoryUsedMb(metrics.getMemoryUsedMb());
        broker.setMemoryTotalMb(metrics.getMemoryTotalMb());
        broker.setDiskUsedGb(metrics.getDiskUsedGb());
        broker.setDiskTotalGb(metrics.getDiskTotalGb());
        broker.setMessagesInPerSec(metrics.getMessagesInPerSec());
        broker.setBytesInPerSec(metrics.getBytesInPerSec());
        broker.setLastSeen(OffsetDateTime.now().toString());
        broker.setLastSeen(OffsetDateTime.now().toString());

        externalClusterNodeRepository.upsertTelemetry(
                cluster.getId(),
                broker.getHostname(),
                broker.getCpuUsagePct(),
                broker.getMemoryUsedMb(),
                broker.getMemoryTotalMb(),
                broker.getDiskUsedGb(),
                broker.getDiskTotalGb(),
                OffsetDateTime.now()
        );

        discoveryAgentRepository.findByHostname(broker.getHostname()).ifPresent(agent -> {
            agent.setStatus("ONLINE");
            agent.setLastHeartbeat(OffsetDateTime.now());
            discoveryAgentRepository.save(agent);
        });
    }

    public List<Map<String, Object>> listExternalClusters() {
        return externalClusterRepository.findByStatusNot("DELETED").stream()
                .sorted(Comparator.comparing(ExternalCluster::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toSummary)
                .toList();
    }

    public Map<String, Object> queueRestart(UUID clusterId) {
        ExternalCluster cluster = externalClusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("External cluster not found."));

        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster).stream()
                .filter(this::isAgentRecord)
                .toList();
        if (brokers.isEmpty()) {
            throw new IllegalArgumentException("No discovery agent is attached to this external cluster.");
        }

        String taskId = UUID.randomUUID().toString();
        for (ExternalBrokerRecord broker : brokers) {
            ExternalAgentTask task = new ExternalAgentTask();
            task.setTaskId(taskId);
            task.setTask("RESTART");
            task.setStatus("PENDING");
            task.setClusterName(cluster.getName());
            task.setHostname(broker.getHostname());
            task.setBootstrap(broker.getBootstrap());
            pendingTasks.put(taskKey(cluster.getName(), broker.getHostname(), broker.getBootstrap()), task);
        }

        return Map.of("taskId", taskId, "status", "queued", "brokers", String.valueOf(brokers.size()));
    }

    public Map<String, Object> queueConfigUpdate(UUID clusterId, String configKey, String configValue, boolean restart) {
        ExternalCluster cluster = externalClusterRepository.findById(clusterId)
                .orElseThrow(() -> new IllegalArgumentException("External cluster not found."));

        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster).stream()
                .filter(this::isAgentRecord)
                .toList();
        if (brokers.isEmpty()) {
            throw new IllegalArgumentException("No discovery agent is attached to this external cluster.");
        }

        String taskId = UUID.randomUUID().toString();
        for (ExternalBrokerRecord broker : brokers) {
            ExternalAgentTask task = new ExternalAgentTask();
            task.setTaskId(taskId);
            task.setTask("UPDATE_CONFIG");
            task.setStatus("PENDING");
            task.setClusterName(cluster.getName());
            task.setHostname(broker.getHostname());
            task.setBootstrap(broker.getBootstrap());
            task.setConfigKey(configKey);
            task.setConfigValue(configValue);
            task.setRestart(restart);
            pendingTasks.put(taskKey(cluster.getName(), broker.getHostname(), broker.getBootstrap()), task);
        }

        return Map.of("taskId", taskId, "status", "queued", "brokers", String.valueOf(brokers.size()));
    }

    public Map<String, String> pollAgentTask(String clusterName, String hostname, String bootstrap) {
        ExternalAgentTask task = pendingTasks.get(taskKey(clusterName, hostname, bootstrap));
        if (task == null || !"PENDING".equals(task.getStatus())) {
            return Map.of("task", "NONE");
        }
        task.setStatus("IN_PROGRESS");
        Map<String, String> response = new LinkedHashMap<>();
        response.put("task", task.getTask());
        response.put("taskId", task.getTaskId());
        if (task.getConfigKey() != null) {
            response.put("configKey", task.getConfigKey());
        }
        if (task.getConfigValue() != null) {
            response.put("configValue", task.getConfigValue());
        }
        response.put("restart", String.valueOf(task.isRestart()));
        return response;
    }

    public void completeAgentTask(String clusterName, String hostname, String bootstrap, AgentTaskCompletion completion) {
        ExternalAgentTask task = pendingTasks.get(taskKey(clusterName, hostname, bootstrap));
        if (task == null) {
            return;
        }
        task.setStatus(blankToDefault(completion.getStatus(), "SUCCESS"));
        task.setMessage(completion.getMessage());
        if (!"FAILED".equalsIgnoreCase(task.getStatus())) {
            pendingTasks.remove(taskKey(clusterName, hostname, bootstrap));
        }
    }

    public String getExternalTaskStatus(String taskId) {
        boolean anyPending = false;
        boolean anyFailed = false;
        List<String> messages = new ArrayList<>();
        for (ExternalAgentTask task : pendingTasks.values()) {
            if (!safeEquals(task.getTaskId(), taskId)) {
                continue;
            }
            if ("FAILED".equalsIgnoreCase(task.getStatus())) {
                anyFailed = true;
            }
            if (!"SUCCESS".equalsIgnoreCase(task.getStatus())) {
                anyPending = true;
            }
            if (task.getMessage() != null && !task.getMessage().isBlank()) {
                messages.add(task.getMessage());
            }
        }
        if (anyFailed) {
            return "FAILED: " + String.join("; ", messages);
        }
        if (anyPending) {
            return "External agent task is still running...";
        }
        return "COMPLETED successfully.";
    }

    public boolean isAgentManaged(ExternalCluster cluster) {
        if (cluster == null) {
            return false;
        }
        return readBrokerRecords(cluster).stream().anyMatch(this::isAgentRecord);
    }

    public List<ExternalBrokerRecord> brokerRecords(ExternalCluster cluster) {
        return readBrokerRecords(cluster);
    }

    private Optional<ExternalCluster> findExternalCluster(String kafkaClusterId, String name, String bootstrapServers) {
        if (kafkaClusterId != null && !kafkaClusterId.isBlank()) {
            for (ExternalCluster cluster : externalClusterRepository.findByStatusNot("DELETED")) {
                if (safeEquals(cluster.getKafkaClusterId(), kafkaClusterId)) {
                    return Optional.of(cluster);
                }
            }
        }
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            Optional<ExternalCluster> byBootstrap = externalClusterRepository.findByBootstrapServersAndStatusNot(bootstrapServers.trim(), "DELETED");
            if (byBootstrap.isPresent()) {
                return byBootstrap;
            }
        }
        if (name != null && !name.isBlank()) {
            return externalClusterRepository.findByNameAndStatusNot(name.trim(), "DELETED");
        }
        return Optional.empty();
    }

    private Map<String, Object> toSummary(ExternalCluster cluster) {
        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster);
        long agentCount = brokers.stream().filter(this::isAgentRecord).count();
        long freshAgents = brokers.stream().filter(this::isFreshAgent).count();
        int brokerCount = cluster.getBrokerCount() != null ? cluster.getBrokerCount() : (brokers.isEmpty() ? 0 : brokers.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", cluster.getId());
        summary.put("name", cluster.getName());
        summary.put("clusterId", blankToDefault(cluster.getKafkaClusterId(), ""));
        summary.put("kafkaVersion", cluster.getKafkaVersion());
        summary.put("kafkaMode", blankToDefault(cluster.getKafkaMode(), "Unknown"));
        summary.put("security", blankToDefault(cluster.getSecurity(), "PLAINTEXT"));
        summary.put("bootstrapServers", cluster.getBootstrapServers());
        summary.put("environment", cluster.getEnvironment());
        summary.put("brokerCount", brokerCount);
        summary.put("agentCount", agentCount);
        summary.put("managementLevel", agentCount > 0 ? "AGENT_MANAGED" : "BOOTSTRAP_ONLY");
        summary.put("managementLabel", agentCount > 0 ? "Agent managed" : "Bootstrap only");
        summary.put("health", agentCount > 0
                ? (freshAgents == agentCount ? "Agent online" : "Agent stale")
                : "Bootstrap registered");
        summary.put("createdAt", cluster.getCreatedAt());
        summary.put("lastSeen", brokers.stream().map(ExternalBrokerRecord::getLastSeen).filter(value -> value != null && !value.isBlank()).max(String::compareTo).orElse(""));
        summary.put("installPath", blankToDefault(cluster.getInstallPath(), ""));
        summary.put("logDirs", blankToDefault(cluster.getLogDirs(), ""));
        return summary;
    }

    private Map<String, Object> toDiscoverySummary(String discoveryKey, ExternalDiscoveryReport report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("discoveryKey", discoveryKey);
        summary.put("name", report.getName());
        summary.put("hostname", report.getHostname());
        summary.put("bootstrapServers", report.getBootstrapServers());
        summary.put("kafkaVersion", blankToDefault(report.getKafkaVersion(), "Unknown"));
        summary.put("kafkaMode", blankToDefault(report.getKafkaMode(), "Unknown"));
        summary.put("security", blankToDefault(report.getSecurity(), "PLAINTEXT"));
        summary.put("brokerCount", report.getBrokerCount());
        summary.put("nodeId", report.getNodeId());
        summary.put("environment", blankToDefault(report.getEnvironment(), "unknown"));
        summary.put("installPath", blankToDefault(report.getInstallPath(), ""));
        summary.put("logDirs", blankToDefault(report.getLogDirs(), ""));
        summary.put("running", report.isRunning());
        summary.put("health", report.isRunning() ? "Agent online" : "Agent reported stopped");
        summary.put("lastSeen", report.getLastSeen());
        summary.put("kafkaClusterId", blankToDefault(report.getKafkaClusterId(), ""));
        return summary;
    }

    private void upsertBrokerRecord(ExternalCluster cluster, ExternalBrokerRecord record) {
        OffsetDateTime lastSeen = null;
        try {
            if (record.getLastSeen() != null && !record.getLastSeen().isBlank()) {
                lastSeen = OffsetDateTime.parse(record.getLastSeen());
            }
        } catch (Exception e) {
            lastSeen = OffsetDateTime.now();
        }
        
        externalClusterNodeRepository.upsertTelemetry(
                cluster.getId(),
                record.getHostname(),
                record.getCpuUsagePct(),
                record.getMemoryUsedMb(),
                record.getMemoryTotalMb(),
                record.getDiskUsedGb(),
                record.getDiskTotalGb(),
                lastSeen != null ? lastSeen : OffsetDateTime.now()
        );
    }

    private void upsertDiscoveryAgent(ExternalDiscoveryReport report, ExternalCluster cluster) {
        String agentId = report.getHostId() == null || report.getHostId().isBlank() 
                ? discoveryHostId(report) 
                : report.getHostId();
                
        io.translab.tantor.server.domain.DiscoveryAgent agent = discoveryAgentRepository.findById(agentId)
                .orElseGet(io.translab.tantor.server.domain.DiscoveryAgent::new);
                
        agent.setId(agentId);
        agent.setAgentName(report.getAgentName());
        agent.setHostname(blankToDefault(report.getHostname(), extractHostFromBootstrap(report.getBootstrapServers())));
        agent.setIpAddresses(blankToDefault(report.getIpAddresses(), writeJson(List.of(extractHostFromBootstrap(report.getBootstrapServers())))));
        agent.setVersion("tantor-discovery-agent");
        agent.setStatus(report.isRunning() ? "ONLINE" : "OFFLINE");
        agent.setLastHeartbeat(OffsetDateTime.now());
        if (cluster != null) {
            agent.setClusterId(cluster.getId());
        }
        discoveryAgentRepository.save(agent);
    }

    private int nextNodeId(UUID clusterId) {
        return clusterServiceAssignmentRepository.findByClusterId(clusterId).stream()
                .map(ClusterServiceAssignment::getNodeId)
                .filter(value -> value != null && value > 0)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private ExternalBrokerRecord buildAgentBrokerRecord(ExternalDiscoveryReport report) {
        ExternalBrokerRecord record = new ExternalBrokerRecord();
        record.setHostname(report.getHostname());
        record.setBootstrap(report.getBootstrapServers());
        record.setKafkaMode(report.getKafkaMode());
        record.setSecurity(report.getSecurity());
        record.setInstallPath(report.getInstallPath());
        record.setLogDirs(report.getLogDirs());
        record.setRunning(report.isRunning());
        record.setRole(report.getKafkaMode() != null && report.getKafkaMode().equalsIgnoreCase("zookeeper") ? "broker" : "broker_controller");
        record.setNodeId(report.getNodeId());
        record.setLastSeen(report.getLastSeen());
        record.setListeners(report.getListeners());
        record.setAdvertisedListeners(report.getAdvertisedListeners());
        record.setProcessRoles(report.getProcessRoles());
        
        // Map Telemetry
        record.setCpuUsagePct(report.getCpuUsagePct());
        record.setMemoryUsedMb(report.getMemoryUsedMb());
        record.setMemoryTotalMb(report.getMemoryTotalMb());
        record.setDiskUsedGb(report.getDiskUsedGb());
        record.setDiskTotalGb(report.getDiskTotalGb());
        
        return record;
    }

    @SuppressWarnings("unchecked")
    private List<ExternalBrokerRecord> buildBootstrapBrokerRecords(Map<String, Object> inspection) {
        List<ExternalBrokerRecord> records = new ArrayList<>();
        Object brokersObj = inspection.get("brokers");
        if (brokersObj instanceof List<?> brokers) {
            for (Object item : brokers) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Object host = map.get("host");
                Object endpoint = map.get("endpoint");
                ExternalBrokerRecord record = new ExternalBrokerRecord();
                record.setHostname(host == null ? "" : String.valueOf(host));
                record.setBootstrap(endpoint == null ? "" : String.valueOf(endpoint));
                record.setNodeId(intValue(map.get("id"), 0));
                record.setRole("broker");
                record.setLastSeen(OffsetDateTime.now().toString());
                records.add(record);
            }
        }
        return records;
    }

    private Map<String, Object> metadata(String managementMode, String kafkaMode, String security, Map<String, Object> inspection, ExternalDiscoveryReport report) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("managementMode", managementMode);
        metadata.put("kafkaMode", blankToDefault(kafkaMode, "Unknown"));
        metadata.put("security", blankToDefault(security, "PLAINTEXT"));
        metadata.put("lastDiscoveryAt", OffsetDateTime.now().toString());
        if (inspection != null) {
            metadata.put("kafkaClusterId", inspection.getOrDefault("clusterId", ""));
            metadata.put("brokerCount", inspection.getOrDefault("brokerCount", 0));
            metadata.put("topicCount", inspection.getOrDefault("topicCount", 0));
            metadata.put("controllerId", inspection.getOrDefault("controllerId", ""));
        }
        if (report != null) {
            metadata.put("kafkaClusterId", blankToDefault(report.getKafkaClusterId(), ""));
            metadata.put("brokerCount", report.getBrokerCount());
            metadata.put("installPath", blankToDefault(report.getInstallPath(), ""));
            metadata.put("logDirs", blankToDefault(report.getLogDirs(), ""));
            metadata.put("isRunning", report.isRunning());
        }
        return metadata;
    }

    private String resolveClusterName(String requestedName, Map<String, Object> inspection) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        String clusterId = String.valueOf(inspection.getOrDefault("clusterId", "external"));
        String suffix = clusterId.length() > 8 ? clusterId.substring(0, 8) : clusterId;
        return "external-" + suffix;
    }

    private List<ExternalBrokerRecord> readBrokerRecords(ExternalCluster cluster) {
        List<io.translab.tantor.server.domain.ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(cluster.getId());
        List<ExternalBrokerRecord> records = new ArrayList<>();
        for (io.translab.tantor.server.domain.ExternalClusterNode n : nodes) {
            ExternalBrokerRecord r = new ExternalBrokerRecord();
            r.setHostname(n.getHost());
            r.setBootstrap(cluster.getBootstrapServers());
            boolean isBroker = Boolean.TRUE.equals(n.getIsBroker());
            boolean isController = Boolean.TRUE.equals(n.getIsController());
            if (isBroker && isController) r.setRole("broker_controller");
            else if (isBroker) r.setRole("broker");
            else if (isController) r.setRole("controller");
            else r.setRole("unknown");
            r.setNodeId(n.getNodeId());
            if (n.getLastSeen() != null) r.setLastSeen(n.getLastSeen().toString());
            r.setCpuUsagePct(n.getCpuUsagePct());
            r.setMemoryUsedMb(n.getMemoryUsedMb());
            r.setMemoryTotalMb(n.getMemoryTotalMb());
            r.setDiskUsedGb(n.getDiskUsedGb());
            r.setDiskTotalGb(n.getDiskTotalGb());
            records.add(r);
        }
        return records;
    }

    private Map<String, Object> readMetadata(Cluster cluster) {
        if (cluster.getConfigJson() == null || cluster.getConfigJson().isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(cluster.getConfigJson(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize external cluster metadata.", e);
        }
    }

    private String discoveryHostId(ExternalDiscoveryReport report) {
        return discoveryHostId(report.getHostname(), report.getBootstrapServers());
    }

    private String discoveryHostId(String hostname, String bootstrapServers) {
        String source = blankToDefault(hostname, "")
                + "|" + extractHostFromBootstrap(bootstrapServers);
        UUID stable = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "discovery-" + stable.toString().substring(0, 18);
    }

    private Optional<Host> findDiscoveryHost(String hostname, String bootstrapServers) {
        String effectiveHostname = blankToDefault(hostname, extractHostFromBootstrap(bootstrapServers));
        String stableId = discoveryHostId(effectiveHostname, bootstrapServers);
        return hostRepository.findById(stableId)
                .or(() -> hostRepository.findFirstByHostnameAndAgentVersion(effectiveHostname, "tantor-discovery-agent"));
    }

    private String taskKey(String clusterName, String hostname, String bootstrap) {
        return blankToDefault(clusterName, "") + "|" + blankToDefault(hostname, "") + "|" + blankToDefault(bootstrap, "");
    }

    private String discoveryKey(ExternalDiscoveryReport report) {
        String source = blankToDefault(report.getKafkaClusterId(), "")
                + "|" + blankToDefault(report.getName(), "")
                + "|" + blankToDefault(report.getHostname(), "")
                + "|" + blankToDefault(report.getBootstrapServers(), "");
        return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isAgentRecord(ExternalBrokerRecord record) {
        return record.getInstallPath() != null && !record.getInstallPath().isBlank();
    }

    private boolean isFreshAgent(ExternalBrokerRecord record) {
        try {
            OffsetDateTime seen = OffsetDateTime.parse(record.getLastSeen());
            return seen.isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isFreshDiscovery(ExternalDiscoveryReport report) {
        try {
            OffsetDateTime seen = OffsetDateTime.parse(report.getLastSeen());
            return seen.isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS));
        } catch (Exception e) {
            return false;
        }
    }

    private ExternalDiscoveryReport requiredPendingDiscovery(String discoveryKey) {
        ExternalDiscoveryReport report = pendingDiscoveries.get(discoveryKey);
        if (report == null || !isFreshDiscovery(report)) {
            throw new IllegalArgumentException("No live discovery-agent report was found. Refresh after the agent reports again.");
        }
        return report;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private void copyBroker(ExternalBrokerRecord from, ExternalBrokerRecord to) {
        to.setHostname(from.getHostname());
        to.setBootstrap(from.getBootstrap());
        to.setKafkaMode(from.getKafkaMode());
        to.setSecurity(from.getSecurity());
        to.setInstallPath(from.getInstallPath());
        to.setLogDirs(from.getLogDirs());
        to.setRunning(from.isRunning());
        to.setRole(from.getRole());
        to.setNodeId(from.getNodeId());
        to.setLastSeen(from.getLastSeen());
        to.setListeners(from.getListeners());
        to.setAdvertisedListeners(from.getAdvertisedListeners());
        to.setProcessRoles(from.getProcessRoles());
    }

    private int intValue(Object value, int defaultValue) {
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

    private String extractHostFromBootstrap(String bootstrap) {
        if (bootstrap == null || bootstrap.isBlank()) {
            return "";
        }
        String first = bootstrap.split(",")[0].trim();
        if (first.contains("://")) {
            first = first.substring(first.indexOf("://") + 3);
        }
        int idx = first.lastIndexOf(":");
        return idx > 0 ? first.substring(0, idx) : first;
    }

    private String mergeBootstrapServers(String existing, String reported) {
        Map<String, Boolean> endpoints = new LinkedHashMap<>();
        for (String value : List.of(blankToDefault(existing, ""), blankToDefault(reported, ""))) {
            for (String endpoint : value.split(",")) {
                String normalized = endpoint.trim();
                if (!normalized.isBlank()) {
                    endpoints.put(normalized, true);
                }
            }
        }
        return String.join(",", endpoints.keySet());
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private boolean safeEquals(String left, String right) {
        return left != null && right != null && left.equals(right);
    }

    @Data
    public static class BootstrapExternalClusterRequest {
        private String name;
        private String environment;
        private String bootstrapServers;
        private String kafkaVersion;
        private String kafkaMode;
        private String security;
        private String clusterId;
        private Integer brokerCount;
        private Boolean agentFound;
        private String discoveryKey;
        private String controllerId;
        private java.util.List<java.util.Map<String, Object>> brokers;
        private java.util.Map<String, String> selectedAgents;
    }

    @Data
    public static class ExternalDiscoveryReport {
        private String hostId;
        private String agentName;
        private String name;
        private String environment;
        private String bootstrapServers;
        private String kafkaVersion;
        private String kafkaClusterId;
        private String kafkaMode;
        private String security;
        private int brokerCount;
        private Integer nodeId;
        @JsonProperty("isRunning")
        private boolean isRunning;
        private String installPath;
        private String logDirs;
        private String configFile;
        private String dataDirs;
        private String hostname;
        private String ipAddresses;
        private String lastSeen;
        private String listeners;
        private String advertisedListeners;
        private String processRoles;
        private Double cpuUsagePct;
        private Long memoryUsedMb;
        private Long memoryTotalMb;
        private Long diskUsedGb;
        private Long diskTotalGb;
    }

    @Data
    public static class ExternalBrokerMetricsDto {
        private String hostname;
        private String bootstrap;
        private Double cpuUsagePct;
        private Long memoryUsedMb;
        private Long memoryTotalMb;
        private Long diskUsedGb;
        private Long diskTotalGb;
        private Double messagesInPerSec;
        private Double bytesInPerSec;
    }

    @Data
    public static class ExternalBrokerRecord {
        private String hostname;
        private String bootstrap;
        private String kafkaMode;
        private String security;
        private String installPath;
        private String logDirs;
        private String role;
        private Integer nodeId;
        private boolean running;
        private String lastSeen;
        private Double cpuUsagePct;
        private Long memoryUsedMb;
        private Long memoryTotalMb;
        private Long diskUsedGb;
        private Long diskTotalGb;
        private Double messagesInPerSec;
        private Double bytesInPerSec;
        private String listeners;
        private String advertisedListeners;
        private String processRoles;
    }

    @Data
    public static class ExternalAgentTask {
        private String taskId;
        private String task;
        private String status;
        private String clusterName;
        private String hostname;
        private String bootstrap;
        private String configKey;
        private String configValue;
        private boolean restart;
        private String message;
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void checkExternalClustersHealth() {
        List<ExternalCluster> externalClusters = externalClusterRepository.findByStatusNot("DELETED");
        for (ExternalCluster cluster : externalClusters) {
            try {
                String previousStatus = cluster.getStatus();
                Map<String, Object> adminData = kafkaAdminService.inspectBootstrapServers(cluster.getBootstrapServers());
                boolean connected = Boolean.TRUE.equals(adminData.get("connected"));
                
                String newStatus;
                if (!connected) {
                    newStatus = "FAILED";
                } else {
                    // Check if discovery agent is healthy
                    boolean agentHealthy = discoveryAgentRepository.findByClusterId(cluster.getId())
                        .stream()
                        .anyMatch(agent -> "ONLINE".equalsIgnoreCase(agent.getStatus()) 
                                && agent.getLastHeartbeat() != null 
                                && agent.getLastHeartbeat().isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS)));
                    
                    newStatus = agentHealthy ? "SUCCESS" : "DEGRADED";
                }
                
                if (!newStatus.equals(previousStatus)) {
                    cluster.setStatus(newStatus);
                    externalClusterRepository.save(cluster);
                    
                    if ("DEGRADED".equals(newStatus)) {
                        activityAlertService.createAlert("WARNING", "External Cluster Degraded", 
                            "The Discovery Agent for external cluster '" + cluster.getName() + "' has stopped reporting, but Kafka is still reachable.", cluster.getId());
                    } else if ("FAILED".equals(newStatus)) {
                        activityAlertService.createAlert("CRITICAL", "External Cluster Failed", 
                            "Kafka Admin API cannot reach external cluster '" + cluster.getName() + "'.", cluster.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to check health for external cluster {}", cluster.getName(), e);
            }
        }
    }

    @Data
    public static class AgentTaskCompletion {
        private String status;
        private String message;
    }
}
