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
import io.translab.tantor.server.service.ExternalClusterService.ExternalAgentTask;

import io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerMetricsDto;

import io.translab.tantor.server.service.ExternalClusterService.ExternalBrokerRecord;
import io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport;
import io.translab.tantor.server.service.ExternalClusterService.AgentTaskCompletion;
import io.translab.tantor.server.service.ExternalClusterService.BootstrapExternalClusterRequest;
import io.translab.tantor.server.domain.ExternalClusterNode;

import io.translab.tantor.server.domain.DiscoveryAgent;
import io.translab.tantor.server.security.EncryptionService;
import io.translab.tantor.server.security.TruststoreStorageService;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import io.translab.tantor.server.repository.ExternalClusterRepository;

@org.springframework.stereotype.Service
@lombok.RequiredArgsConstructor
public class ExternalClusterDiscoveryService {
    public static final long AGENT_STALE_SECONDS = 180;
    private final io.translab.tantor.server.repository.DiscoveryAgentRepository discoveryAgentRepository;
    private final io.translab.tantor.server.repository.ExternalClusterNodeRepository externalClusterNodeRepository;
    private final io.translab.tantor.server.repository.HostRepository hostRepository;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final ExternalClusterQueryService externalClusterQueryService;
    private final java.util.Map<String, ExternalDiscoveryReport> pendingDiscoveries = new java.util.concurrent.ConcurrentHashMap<>();

    public java.util.Map<String, ExternalDiscoveryReport> getPendingDiscoveries() { return pendingDiscoveries; }
    public ExternalDiscoveryReport getPendingDiscovery(String key) { return pendingDiscoveries.get(key); }

    public List<Map<String, Object>> listPendingDiscoveries() {
        return getPendingDiscoveries().entrySet().stream()
                .filter(entry -> externalClusterQueryService.findExternalCluster(
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


    @Transactional
    public Map<String, Object> recordDiscoveryAgentHeartbeat(ExternalDiscoveryReport report) {
        report.setLastSeen(OffsetDateTime.now().toString());
        if (report.getHostname() == null || report.getHostname().isBlank()) {
            report.setHostname(ExternalClusterUtil.extractHostFromBootstrap(report.getBootstrapServers()));
        }
        upsertDiscoveryAgent(report, null);
        return Map.of(
                "status", "online",
                "agentId", report.getHostId() == null || report.getHostId().isBlank() ? discoveryHostId(report) : report.getHostId(),
                "lastHeartbeat", report.getLastSeen()
        );
    }


    public List<Map<String, Object>> listDiscoveryAgents() {
        OffsetDateTime now = OffsetDateTime.now();
        return discoveryAgentRepository.findAll().stream()
                .sorted(Comparator.comparing(
                        DiscoveryAgent::getLastHeartbeat,
                        Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .map(agent -> {
                    boolean fresh = agent.getLastHeartbeat() != null
                            && agent.getLastHeartbeat().isAfter(now.minusSeconds(AGENT_STALE_SECONDS));
                    Map<String, Object> summary = new LinkedHashMap<>();
                    summary.put("id", agent.getId());
                    summary.put("agentName", ExternalClusterUtil.blankToDefault(agent.getAgentName(), agent.getId()));
                    summary.put("hostname", ExternalClusterUtil.blankToDefault(agent.getHostname(), ""));
                    summary.put("ipAddresses", ExternalClusterUtil.blankToDefault(agent.getIpAddresses(), "[]"));
                    summary.put("version", ExternalClusterUtil.blankToDefault(agent.getVersion(), "tantor-discovery-agent"));
                    summary.put("canExecuteTasks", Boolean.TRUE.equals(agent.getCanExecuteTasks()));
                    summary.put("clusterId", agent.getClusterId());
                    summary.put("lastHeartbeat", agent.getLastHeartbeat());
                    summary.put("fresh", fresh);
                    summary.put("status", fresh ? "ONLINE" : "STALE");
                    summary.put("health", fresh ? "green" : "orange");
                    summary.put("stateLabel", agent.getClusterId() == null
                            ? (fresh ? "Online - no cluster connected" : "Stale - no recent polling")
                            : (fresh ? "Online - cluster connected" : "Stale - cluster connection needs attention"));
                    return summary;
                })
                .toList();
    }




    @Transactional
    public void receiveMetrics(String clusterName, ExternalBrokerMetricsDto metrics) {
        Optional<ExternalCluster> clusterOpt = externalClusterQueryService.findExternalCluster(null, clusterName, metrics.getBootstrap());
        if (clusterOpt.isEmpty()) {
            return;
        }

        ExternalCluster cluster = clusterOpt.get();
        List<ExternalBrokerRecord> brokers = readBrokerRecords(cluster);
        String bootstrap = ExternalClusterUtil.blankToDefault(metrics.getBootstrap(), cluster.getBootstrapServers());
        ExternalBrokerRecord broker = brokers.stream()
                .filter(item -> ExternalClusterUtil.safeEquals(item.getHostname(), metrics.getHostname()) 
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


    public List<ExternalBrokerRecord> readBrokerRecords(ExternalCluster cluster) {
        List<ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(cluster.getId());
        List<DiscoveryAgent> agents = discoveryAgentRepository.findByClusterId(cluster.getId());
        List<ExternalBrokerRecord> records = new ArrayList<>();
        for (ExternalClusterNode n : nodes) {
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
            Optional<DiscoveryAgent> agent = agents.stream()
                    .filter(candidate -> matchesDiscoveryAgent(candidate, n.getHost()))
                    .findFirst();
            OffsetDateTime lastSeen = n.getLastSeen();
            if (lastSeen == null && agent.isPresent()) {
                lastSeen = agent.get().getLastHeartbeat();
            }
            if (lastSeen != null) r.setLastSeen(lastSeen.toString());
            r.setCpuUsagePct(n.getCpuUsagePct());
            r.setMemoryUsedMb(n.getMemoryUsedMb());
            r.setMemoryTotalMb(n.getMemoryTotalMb());
            r.setDiskUsedGb(n.getDiskUsedGb());
            r.setDiskTotalGb(n.getDiskTotalGb());
            r.setInstallPath(ExternalClusterUtil.blankToDefault(n.getInstallDir(), cluster.getInstallPath()));
            r.setLogDirs(ExternalClusterUtil.blankToDefault(n.getLogDirs(), cluster.getLogDirs()));
            r.setRunning(lastSeen != null && lastSeen.isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS)));
            records.add(r);
        }
        return records;
    }


    public void upsertDiscoveryAgent(ExternalDiscoveryReport report, ExternalCluster cluster) {
        String agentId = report.getHostId() == null || report.getHostId().isBlank() 
                ? discoveryHostId(report) 
                : report.getHostId();
                
        io.translab.tantor.server.domain.DiscoveryAgent agent = discoveryAgentRepository.findById(agentId)
                .orElseGet(io.translab.tantor.server.domain.DiscoveryAgent::new);
                
        agent.setId(agentId);
        agent.setAgentName(report.getAgentName());
        agent.setHostname(ExternalClusterUtil.blankToDefault(report.getHostname(), ExternalClusterUtil.extractHostFromBootstrap(report.getBootstrapServers())));
        agent.setIpAddresses(ExternalClusterUtil.blankToDefault(report.getIpAddresses(), ExternalClusterUtil.writeJson(List.of(ExternalClusterUtil.extractHostFromBootstrap(report.getBootstrapServers())))));
        agent.setVersion("tantor-discovery-agent");
        agent.setStatus(report.isRunning() ? "ONLINE" : "OFFLINE");
        agent.setCanExecuteTasks(report.isCanExecuteTasks());
        agent.setLastHeartbeat(OffsetDateTime.now());
        if (cluster != null) {
            agent.setClusterId(cluster.getId());
        }
        discoveryAgentRepository.save(agent);
    }


    public void linkDiscoveryAgent(DiscoveryAgent agent, ExternalCluster cluster) {
        if (agent == null || cluster == null) {
            return;
        }
        agent.setClusterId(cluster.getId());
        agent.setStatus("ONLINE");
        agent.setLastHeartbeat(OffsetDateTime.now());
        discoveryAgentRepository.save(agent);
    }


    public boolean isAgentManaged(ExternalCluster cluster) {
        if (cluster == null) {
            return false;
        }
        return discoveryAgentRepository.findByClusterId(cluster.getId()).stream()
                .anyMatch(agent -> "ONLINE".equalsIgnoreCase(agent.getStatus()));
    }


    public boolean isFreshDiscovery(ExternalDiscoveryReport report) {
        try {
            OffsetDateTime seen = OffsetDateTime.parse(report.getLastSeen());
            return seen.isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS));
        } catch (Exception e) {
            return false;
        }
    }


    public boolean isFreshAgent(ExternalBrokerRecord record) {
        try {
            OffsetDateTime seen = OffsetDateTime.parse(record.getLastSeen());
            return seen.isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS));
        } catch (Exception e) {
            return false;
        }
    }


    public boolean isFreshAgent(DiscoveryAgent agent) {
        return agent.getLastHeartbeat() != null
                && agent.getLastHeartbeat().isAfter(OffsetDateTime.now().minusSeconds(AGENT_STALE_SECONDS));
    }


    public String discoveryHostId(ExternalDiscoveryReport report) {
        return discoveryHostId(report.getHostname(), report.getBootstrapServers());
    }


    public String discoveryHostId(String hostname, String bootstrapServers) {
        String source = ExternalClusterUtil.blankToDefault(hostname, "")
                + "|" + ExternalClusterUtil.extractHostFromBootstrap(bootstrapServers);
        UUID stable = UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return "discovery-" + stable.toString().substring(0, 18);
    }


    private Optional<Host> findDiscoveryHost(String hostname, String bootstrapServers) {
        String effectiveHostname = ExternalClusterUtil.blankToDefault(hostname, ExternalClusterUtil.extractHostFromBootstrap(bootstrapServers));
        String stableId = discoveryHostId(effectiveHostname, bootstrapServers);
        return hostRepository.findById(stableId)
                .or(() -> hostRepository.findFirstByHostnameAndAgentVersion(effectiveHostname, "tantor-discovery-agent"));
    }


    private Set<String> discoveryHostCandidates(ExternalDiscoveryReport report, DiscoveryAgent agent) {
        Set<String> candidates = new HashSet<>();
        addCandidate(candidates, report.getHostname());
        addCandidate(candidates, ExternalClusterUtil.extractHostFromBootstrap(report.getBootstrapServers()));
        if (agent != null) {
            addCandidate(candidates, agent.getHostname());
            candidates.addAll(parseAgentAddresses(agent.getIpAddresses()));
        }
        return candidates;
    }


    private void addCandidate(Set<String> candidates, String value) {
        if (value != null && !value.isBlank()) {
            candidates.add(value.trim());
        }
    }


    private List<String> parseAgentAddresses(String ipAddresses) {
        if (ipAddresses == null || ipAddresses.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(ipAddresses, new TypeReference<List<String>>() {});
            return values.stream().filter(value -> value != null && !value.isBlank()).toList();
        } catch (Exception ignored) {
            List<String> values = new ArrayList<>();
            for (String part : ipAddresses.replaceAll("\\[|\\]|\\\"", "").split(",")) {
                String value = part.trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }
    }


    private Map<String, Object> toDiscoverySummary(String discoveryKey, ExternalDiscoveryReport report) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("discoveryKey", discoveryKey);
        summary.put("name", report.getName());
        summary.put("hostname", report.getHostname());
        summary.put("bootstrapServers", report.getBootstrapServers());
        summary.put("kafkaVersion", ExternalClusterUtil.blankToDefault(report.getKafkaVersion(), "Unknown"));
        summary.put("kafkaMode", ExternalClusterUtil.blankToDefault(report.getKafkaMode(), "Unknown"));
        summary.put("security", ExternalClusterUtil.blankToDefault(report.getSecurity(), "PLAINTEXT"));
        summary.put("brokerCount", report.getBrokerCount());
        summary.put("nodeId", report.getNodeId());
        summary.put("environment", ExternalClusterUtil.blankToDefault(report.getEnvironment(), "unknown"));
        summary.put("installPath", ExternalClusterUtil.blankToDefault(report.getInstallPath(), ""));
        summary.put("logDirs", ExternalClusterUtil.blankToDefault(report.getLogDirs(), ""));
        summary.put("running", report.isRunning());
        summary.put("health", report.isRunning() ? "Agent online" : "Agent reported stopped");
        summary.put("lastSeen", report.getLastSeen());
        summary.put("kafkaClusterId", ExternalClusterUtil.blankToDefault(report.getKafkaClusterId(), ""));
        return summary;
    }


    private boolean matchesDiscoveryAgent(DiscoveryAgent agent, String host) {
        if (agent == null || host == null || host.isBlank()) {
            return false;
        }
        if (agent.getHostname() != null && agent.getHostname().equalsIgnoreCase(host)) {
            return true;
        }
        return parseAgentAddresses(agent.getIpAddresses()).stream()
                .anyMatch(address -> address.equalsIgnoreCase(host));
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
                record.setNodeId(ExternalClusterUtil.intValue(map.get("id"), 0));
                record.setRole("broker");
                record.setLastSeen(OffsetDateTime.now().toString());
                records.add(record);
            }
        }
        return records;
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


    private String resolveClusterName(String requestedName, Map<String, Object> inspection) {
        if (requestedName != null && !requestedName.isBlank()) {
            return requestedName.trim();
        }
        String clusterId = String.valueOf(inspection.getOrDefault("clusterId", "external"));
        String suffix = clusterId.length() > 8 ? clusterId.substring(0, 8) : clusterId;
        return "external-" + suffix;
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


    public boolean matchesDiscoveryNode(
            ExternalClusterNode node,
            ExternalDiscoveryReport report,
            io.translab.tantor.server.domain.DiscoveryAgent agent
    ) {
        if (node == null) {
            return false;
        }
        if (report.getNodeId() != null && report.getNodeId().equals(node.getNodeId())) {
            return true;
        }
        String nodeHost = node.getHost();
        if (nodeHost == null || nodeHost.isBlank()) {
            return false;
        }
        for (String candidate : discoveryHostCandidates(report, agent)) {
            if (candidate.equalsIgnoreCase(nodeHost)) {
                return true;
            }
        }
        return matchesDiscoveryAgent(agent, nodeHost);
    }


}