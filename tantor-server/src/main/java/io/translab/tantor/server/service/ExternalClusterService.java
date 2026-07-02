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
import io.translab.tantor.server.repository.HostRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalClusterService {

    private static final String EXTERNAL_MODE = "EXTERNAL";
    private static final long AGENT_STALE_SECONDS = 90;

    private final ClusterRepository clusterRepository;
    private final ClusterServiceAssignmentRepository clusterServiceAssignmentRepository;
    private final HostRepository hostRepository;
    private final KafkaAdminService kafkaAdminService;
    private final ObjectMapper objectMapper;
    private final ActivityAlertService activityAlertService;

    private final Map<String, ExternalAgentTask> pendingTasks = new ConcurrentHashMap<>();
    private final Map<String, ExternalDiscoveryReport> pendingDiscoveries = new ConcurrentHashMap<>();

    public Map<String, Object> testBootstrap(String bootstrapServers) {
        try {
            return kafkaAdminService.inspectBootstrapServers(bootstrapServers);
        } catch (RuntimeException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("connected", false);
            result.put("bootstrapServers", bootstrapServers);
            result.put("message", e.getMessage());
            return result;
        }
    }

    @Transactional
    public Cluster registerBootstrapCluster(BootstrapExternalClusterRequest request) {
        if (request.getBootstrapServers() == null || request.getBootstrapServers().isBlank()) {
            throw new IllegalArgumentException("Bootstrap servers are required.");
        }

        String bootstrap = request.getBootstrapServers().trim();
        Map<String, Object> inspection = testBootstrap(bootstrap);
        if (!Boolean.TRUE.equals(inspection.get("connected"))) {
            throw new IllegalArgumentException(String.valueOf(inspection.getOrDefault("message", "Bootstrap connection failed.")));
        }

        Cluster cluster = findExternalCluster(null, request.getName(), bootstrap)
                .orElseGet(Cluster::new);

        if (cluster.getId() == null && request.getName() != null && !request.getName().isBlank()
                && clusterRepository.findByNameAndStatusNot(request.getName().trim(), "DELETED").isPresent()) {
            throw new IllegalArgumentException("A non-deleted cluster with this name already exists.");
        }

        cluster.setName(resolveClusterName(request.getName(), inspection));
        cluster.setMode(EXTERNAL_MODE);
        cluster.setOriginType("EXTERNAL");
        cluster.setKafkaClusterId(blankToDefault(String.valueOf(inspection.getOrDefault("clusterId", "")), null));
        cluster.setKafkaVersion(blankToDefault(request.getKafkaVersion(), "Unknown"));
        cluster.setEnvironment(request.getEnvironment());
        cluster.setBootstrapServers(mergeBootstrapServers(cluster.getBootstrapServers(), bootstrap));
        cluster.setStatus("SUCCESS");
        cluster.setConfigJson(writeJson(metadata("BOOTSTRAP_ONLY", request.getKafkaMode(), request.getSecurity(), inspection, null)));
        cluster.setExternalBrokerHostsJson(writeJson(buildBootstrapBrokerRecords(inspection)));

        Cluster saved = clusterRepository.save(cluster);
        activityAlertService.logActivity("INFO", "Connected bootstrap-only external cluster: " + saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public Map<String, Object> recordDiscoveryReport(ExternalDiscoveryReport report) {
        validateDiscoveryReport(report);
        report.setLastSeen(OffsetDateTime.now().toString());
        upsertDiscoveryHost(report, null);

        Optional<Cluster> connectedCluster = findExternalCluster(report.getKafkaClusterId(), report.getName(), report.getBootstrapServers().trim());
        if (connectedCluster.isPresent()) {
            Cluster cluster = upsertDiscoveryCluster(report);
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
        Map<String, Object> inspection = new LinkedHashMap<>(testBootstrap(report.getBootstrapServers()));

        boolean connected = Boolean.TRUE.equals(inspection.get("connected"));
        String discoveredClusterId = stringValue(inspection.get("clusterId"));
        if (connected && !discoveredClusterId.isBlank()) {
            if (report.getKafkaClusterId() != null && !report.getKafkaClusterId().isBlank()
                    && !report.getKafkaClusterId().equals(discoveredClusterId)) {
                inspection.put("connected", false);
                inspection.put("success", false);
                inspection.put("status", "FAILED");
                inspection.put("message", "The discovery agent cluster ID does not match the bootstrap server cluster ID.");
            } else {
                report.setKafkaClusterId(discoveredClusterId);
            }
        }

        report.setBrokerCount(intValue(inspection.get("brokerCount"), report.getBrokerCount()));
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
        inspection.put("kafkaClusterId", blankToDefault(report.getKafkaClusterId(), discoveredClusterId));
        inspection.put("kafka_cluster_id", blankToDefault(report.getKafkaClusterId(), discoveredClusterId));
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
    public Cluster connectDiscovery(String discoveryKey) {
        Map<String, Object> inspection = inspectDiscovery(discoveryKey);
        if (!Boolean.TRUE.equals(inspection.get("connected"))) {
            throw new IllegalArgumentException(String.valueOf(inspection.getOrDefault(
                    "message",
                    "The discovered Kafka bootstrap server is not reachable."
            )));
        }

        ExternalDiscoveryReport report = requiredPendingDiscovery(discoveryKey);
        Cluster cluster = upsertDiscoveryCluster(report);
        pendingDiscoveries.remove(discoveryKey);
        return cluster;
    }

    @Transactional
    public Cluster upsertDiscoveryCluster(ExternalDiscoveryReport report) {
        validateDiscoveryReport(report);

        String bootstrap = report.getBootstrapServers().trim();
        Cluster cluster = findExternalCluster(report.getKafkaClusterId(), report.getName(), bootstrap)
                .orElseGet(Cluster::new);

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

    private Cluster saveDiscoveryCluster(ExternalDiscoveryReport report, String bootstrap, Cluster cluster) {
        cluster.setName(cluster.getId() == null ? report.getName().trim() : cluster.getName());
        cluster.setMode(EXTERNAL_MODE);
        cluster.setOriginType("EXTERNAL");
        cluster.setKafkaClusterId(blankToDefault(report.getKafkaClusterId(), null));
        cluster.setInstallDirectory(blankToDefault(report.getInstallPath(), null));
        cluster.setConfigDirectory(cluster.getInstallDirectory() == null ? null : cluster.getInstallDirectory() + "/config");
        cluster.setLogDirectory(blankToDefault(report.getLogDirs(), null));
        cluster.setKafkaVersion(blankToDefault(report.getKafkaVersion(), "Unknown"));
        cluster.setEnvironment(blankToDefault(report.getEnvironment(), "unknown"));
        cluster.setBootstrapServers(mergeBootstrapServers(cluster.getBootstrapServers(), bootstrap));
        cluster.setStatus(report.isRunning() ? "SUCCESS" : "FAILED");

        Map<String, Object> metadata = metadata("AGENT_MANAGED", report.getKafkaMode(), report.getSecurity(), null, report);
        cluster.setConfigJson(writeJson(metadata));

        Cluster saved = clusterRepository.save(cluster);
        ExternalBrokerRecord broker = buildAgentBrokerRecord(report);
        upsertBrokerRecord(saved, broker);
        upsertExternalHostAndService(saved, broker, report);

        activityAlertService.logActivity("INFO", "Discovered external cluster via agent: " + saved.getName(), saved.getId());
        return saved;
    }

    @Transactional
    public void receiveMetrics(String clusterName, ExternalBrokerMetricsDto metrics) {
        Optional<Cluster> clusterOpt = clusterRepository.findByModeAndNameAndStatusNot(EXTERNAL_MODE, clusterName, "DELETED");
        if (clusterOpt.isEmpty()) {
            return;
        }

        Cluster cluster = clusterOpt.get();
        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster);
        String bootstrap = blankToDefault(metrics.getBootstrap(), cluster.getBootstrapServers());
        ExternalBrokerRecord broker = brokers.stream()
                .filter(item -> safeEquals(item.getHostname(), metrics.getHostname()) || safeEquals(item.getBootstrap(), bootstrap))
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

        cluster.setExternalBrokerHostsJson(writeJson(brokers));
        clusterRepository.save(cluster);

        findDiscoveryHost(broker.getHostname(), broker.getBootstrap()).ifPresent(host -> {
            host.setCpuUsagePct(metrics.getCpuUsagePct());
            host.setMemUsedMb(metrics.getMemoryUsedMb());
            host.setMemTotalMb(metrics.getMemoryTotalMb());
            host.setDiskUsedGb(metrics.getDiskUsedGb());
            host.setDiskTotalGb(metrics.getDiskTotalGb());
            host.setStatus("ONLINE");
            host.setLastHeartbeat(OffsetDateTime.now());
            hostRepository.save(host);
        });
    }

    public List<Map<String, Object>> listExternalClusters() {
        return clusterRepository.findByModeAndStatusNot(EXTERNAL_MODE, "DELETED").stream()
                .sorted(Comparator.comparing(Cluster::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toSummary)
                .toList();
    }

    public Map<String, Object> queueRestart(UUID clusterId) {
        Cluster cluster = clusterRepository.findById(clusterId)
                .filter(item -> EXTERNAL_MODE.equalsIgnoreCase(item.getMode()))
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
        Cluster cluster = clusterRepository.findById(clusterId)
                .filter(item -> EXTERNAL_MODE.equalsIgnoreCase(item.getMode()))
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

    public boolean isAgentManaged(Cluster cluster) {
        if (cluster == null || !EXTERNAL_MODE.equalsIgnoreCase(cluster.getMode())) {
            return false;
        }
        return readBrokerRecords(cluster).stream().anyMatch(this::isAgentRecord);
    }

    public List<ExternalBrokerRecord> brokerRecords(Cluster cluster) {
        return readBrokerRecords(cluster);
    }

    private Optional<Cluster> findExternalCluster(String kafkaClusterId, String name, String bootstrapServers) {
        if (kafkaClusterId != null && !kafkaClusterId.isBlank()) {
            for (Cluster cluster : clusterRepository.findByModeAndStatusNot(EXTERNAL_MODE, "DELETED")) {
                Map<String, Object> metadata = readMetadata(cluster);
                if (safeEquals(String.valueOf(metadata.get("kafkaClusterId")), kafkaClusterId)) {
                    return Optional.of(cluster);
                }
            }
        }
        if (bootstrapServers != null && !bootstrapServers.isBlank()) {
            Optional<Cluster> byBootstrap = clusterRepository.findByModeAndBootstrapServersAndStatusNot(EXTERNAL_MODE, bootstrapServers.trim(), "DELETED");
            if (byBootstrap.isPresent()) {
                return byBootstrap;
            }
        }
        if (name != null && !name.isBlank()) {
            return clusterRepository.findByModeAndNameAndStatusNot(EXTERNAL_MODE, name.trim(), "DELETED");
        }
        return Optional.empty();
    }

    private Map<String, Object> toSummary(Cluster cluster) {
        Map<String, Object> metadata = readMetadata(cluster);
        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster);
        long agentCount = brokers.stream().filter(this::isAgentRecord).count();
        long freshAgents = brokers.stream().filter(this::isFreshAgent).count();
        int brokerCount = intValue(metadata.get("brokerCount"), brokers.isEmpty() ? 0 : brokers.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", cluster.getId());
        summary.put("name", cluster.getName());
        summary.put("clusterId", metadata.getOrDefault("kafkaClusterId", metadata.getOrDefault("clusterId", "")));
        summary.put("kafkaVersion", cluster.getKafkaVersion());
        summary.put("kafkaMode", metadata.getOrDefault("kafkaMode", "Unknown"));
        summary.put("security", metadata.getOrDefault("security", "PLAINTEXT"));
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
        summary.put("installPath", metadata.getOrDefault("installPath", ""));
        summary.put("logDirs", metadata.getOrDefault("logDirs", ""));
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

    private void upsertBrokerRecord(Cluster cluster, ExternalBrokerRecord record) {
        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster);
        ExternalBrokerRecord existing = brokers.stream()
                .filter(item -> safeEquals(item.getHostname(), record.getHostname()) || safeEquals(item.getBootstrap(), record.getBootstrap()))
                .findFirst()
                .orElse(null);
        if (existing == null) {
            brokers.add(record);
        } else {
            copyBroker(record, existing);
        }
        cluster.setExternalBrokerHostsJson(writeJson(brokers));
        clusterRepository.save(cluster);
    }

    private void upsertExternalHostAndService(Cluster cluster, ExternalBrokerRecord broker, ExternalDiscoveryReport report) {
        Host host = findDiscoveryHost(report.getHostname(), report.getBootstrapServers()).orElseGet(Host::new);
        String hostId = host.getId() == null ? discoveryHostId(report) : host.getId();
        host.setId(hostId);
        host.setHostname(blankToDefault(broker.getHostname(), broker.getBootstrap()));
        host.setIpAddresses(writeJson(List.of(extractHostFromBootstrap(blankToDefault(broker.getBootstrap(), broker.getHostname())))));
        host.setOsDetails("External Kafka host");
        host.setAgentVersion("tantor-discovery-agent");
        host.setStatus(broker.isRunning() ? "ONLINE" : "OFFLINE");
        host.setLastHeartbeat(OffsetDateTime.now());
        host.setClusterId(cluster.getId());
        hostRepository.save(host);

        ClusterServiceAssignment service = clusterServiceAssignmentRepository.findByClusterIdAndHostId(cluster.getId(), hostId)
                .orElseGet(ClusterServiceAssignment::new);
        service.setCluster(cluster);
        service.setHostId(hostId);
        service.setRole("broker");
        Integer nodeId = report.getNodeId();
        service.setNodeId(nodeId != null && nodeId >= 0 ? nodeId : nextNodeId(cluster.getId()));
        clusterServiceAssignmentRepository.save(service);
    }

    private void upsertDiscoveryHost(ExternalDiscoveryReport report, Cluster cluster) {
        Host host = findDiscoveryHost(report.getHostname(), report.getBootstrapServers()).orElseGet(Host::new);
        String hostId = host.getId() == null ? discoveryHostId(report) : host.getId();
        host.setId(hostId);
        host.setHostname(blankToDefault(report.getHostname(), extractHostFromBootstrap(report.getBootstrapServers())));
        host.setIpAddresses(writeJson(List.of(extractHostFromBootstrap(report.getBootstrapServers()))));
        host.setOsDetails("Kafka host discovered by Tantor");
        host.setAgentVersion("tantor-discovery-agent");
        host.setStatus(report.isRunning() ? "ONLINE" : "OFFLINE");
        host.setLastHeartbeat(OffsetDateTime.now());
        if (cluster != null) {
            host.setClusterId(cluster.getId());
        }
        hostRepository.save(host);
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
        record.setRole("broker");
        record.setNodeId(report.getNodeId());
        record.setLastSeen(OffsetDateTime.now().toString());
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

    private List<ExternalBrokerRecord> readBrokerRecords(Cluster cluster) {
        if (cluster.getExternalBrokerHostsJson() == null || cluster.getExternalBrokerHostsJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(cluster.getExternalBrokerHostsJson(), new TypeReference<List<ExternalBrokerRecord>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse external broker records for cluster {}", cluster.getId(), e);
            return new ArrayList<>();
        }
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
        if (report == null || !report.isRunning() || !isFreshDiscovery(report)) {
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
    }

    @Data
    public static class ExternalDiscoveryReport {
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
        private String hostname;
        private String lastSeen;
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

    @Data
    public static class AgentTaskCompletion {
        private String status;
        private String message;
    }
}
