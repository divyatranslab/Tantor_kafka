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
public class ExternalClusterRegistrationService {
    private final io.translab.tantor.server.repository.ExternalClusterRepository externalClusterRepository;
    private final io.translab.tantor.server.repository.ExternalClusterNodeRepository externalClusterNodeRepository;
    private final io.translab.tantor.server.repository.DiscoveryAgentRepository discoveryAgentRepository;
    private final io.translab.tantor.server.security.EncryptionService encryptionService;
    private final io.translab.tantor.server.security.TruststoreStorageService truststoreStorageService;
    private final io.translab.tantor.server.audit.AuditService auditService;
    private final ActivityAlertService activityAlertService;
    private final ExternalClusterConnectionService externalClusterConnectionService;
    private final ExternalClusterDiscoveryService externalClusterDiscoveryService;
    private final ExternalClusterQueryService externalClusterQueryService;


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
            inspection.put("security_protocol", request.getSecurityProtocol() != null ? request.getSecurityProtocol() : request.getSecurity());
            inspection.put("agentFound", request.getAgentFound());
            inspection.put("discoveryKey", request.getDiscoveryKey());
            inspection.put("kafka_version", request.getKafkaVersion());
            inspection.put("mode", request.getKafkaMode());
            inspection.put("controllerId", request.getControllerId());
            inspection.put("brokers", request.getBrokers());
        } else {
            inspection = externalClusterConnectionService.testBootstrap(request);
        }
        
        // Ensure Kafka Admin API was able to connect
        if (!Boolean.TRUE.equals(inspection.get("connected"))) {
            throw new IllegalArgumentException(String.valueOf(inspection.getOrDefault("message", "Bootstrap connection failed.")));
        }

        ExternalCluster savedCluster = null;

        // Create the ExternalCluster entity based on AdminClient data (source of truth)
        String clusterId = String.valueOf(inspection.get("clusterId"));
        savedCluster = externalClusterQueryService.findReusableExternalCluster(clusterId, request.getName(), bootstrap).orElseGet(ExternalCluster::new);
        savedCluster.setName(request.getName() != null ? request.getName().trim() : savedCluster.getName());
        savedCluster.setBootstrapServers(externalClusterQueryService.mergeBootstrapServers(savedCluster.getBootstrapServers(), bootstrap));
        savedCluster.setKafkaClusterId(clusterId);
        savedCluster.setKafkaVersion(ExternalClusterUtil.blankToDefault(ExternalClusterUtil.firstString(inspection, "kafkaVersion", "kafka_version"), "Unknown"));
        savedCluster.setEnvironment(ExternalClusterUtil.blankToDefault(request.getEnvironment(), "unknown"));
        savedCluster.setKafkaMode(ExternalClusterUtil.blankToDefault(ExternalClusterUtil.firstString(inspection, "mode", "kafkaMode", "kafka_mode"), "Unknown"));
        savedCluster.setSecurity(ExternalClusterUtil.blankToDefault(ExternalClusterUtil.firstString(inspection, "security_protocol", "security"), "PLAINTEXT"));
        savedCluster.setSecurityProtocol(request.getSecurityProtocol());
        savedCluster.setSaslMechanism(request.getSaslMechanism());
        savedCluster.setSaslUsername(request.getSaslUsername());
        if (request.getSaslPassword() != null && !request.getSaslPassword().isBlank()) {
            savedCluster.setSaslPasswordEncrypted(encryptionService.encrypt(request.getSaslPassword()));
        }
        
        savedCluster.setDisableHostnameVerification(Boolean.TRUE.equals(request.getDisableHostnameVerification()));
        savedCluster.setTruststoreType(request.getTruststoreType());
        if (request.getTruststorePassword() != null && !request.getTruststorePassword().isBlank()) {
            savedCluster.setTruststorePasswordEncrypted(encryptionService.encrypt(request.getTruststorePassword()));
        }
        savedCluster.setKeystoreType(request.getKeystoreType());
        if (request.getKeystorePassword() != null && !request.getKeystorePassword().isBlank()) {
            savedCluster.setKeystorePasswordEncrypted(encryptionService.encrypt(request.getKeystorePassword()));
        }
        if (request.getKeyPassword() != null && !request.getKeyPassword().isBlank()) {
            savedCluster.setKeyPasswordEncrypted(encryptionService.encrypt(request.getKeyPassword()));
        }
        
        if (inspection.get("brokerCount") instanceof Number) {
            savedCluster.setBrokerCount(((Number) inspection.get("brokerCount")).intValue());
        }
        savedCluster.setStatus("SUCCESS");
        savedCluster = externalClusterRepository.save(savedCluster);

        // Save truststore/keystore files if provided
        if (request.getTruststoreBase64() != null && !request.getTruststoreBase64().isBlank()) {
            savedCluster.setTruststoreContentEncrypted(encryptionService.encrypt(ExternalClusterUtil.normalizeBase64(request.getTruststoreBase64())));
            String path = truststoreStorageService.saveTruststore(savedCluster.getId(), request.getTruststoreType(), request.getTruststoreBase64());
            savedCluster.setTruststorePath(path);
            savedCluster = externalClusterRepository.save(savedCluster);
        }
        if (request.getKeystoreBase64() != null && !request.getKeystoreBase64().isBlank()) {
            savedCluster.setKeystoreContentEncrypted(encryptionService.encrypt(ExternalClusterUtil.normalizeBase64(request.getKeystoreBase64())));
            String path = truststoreStorageService.saveTruststore(savedCluster.getId(), "keystore_" + request.getKeystoreType(), request.getKeystoreBase64());
            savedCluster.setKeystorePath(path);
            savedCluster = externalClusterRepository.save(savedCluster);
        }

        List<ExternalDiscoveryReport> selectedDiscoveryReports = new ArrayList<>();

        // Process Selected Agents
        if (request.getSelectedAgents() != null && !request.getSelectedAgents().isEmpty()) {
            for (Map.Entry<String, String> entry : request.getSelectedAgents().entrySet()) {
                String agentId = entry.getValue();
                // Check if it's a pending discovery
                if (externalClusterDiscoveryService.getPendingDiscoveries().containsKey(agentId)) {
                    ExternalDiscoveryReport report = externalClusterDiscoveryService.getPendingDiscoveries().get(agentId);
                    externalClusterDiscoveryService.upsertDiscoveryAgent(report, savedCluster);
                    selectedDiscoveryReports.add(report);
                    externalClusterDiscoveryService.getPendingDiscoveries().remove(agentId);
                } else {
                    // Update existing db agent to link to this cluster
                    Optional<DiscoveryAgent> optAgent = discoveryAgentRepository.findById(agentId);
                    if (optAgent.isPresent()) {
                        DiscoveryAgent agent = optAgent.get();
                        agent.setClusterId(savedCluster.getId());
                        agent.setStatus("ONLINE");
                        agent.setLastHeartbeat(OffsetDateTime.now());
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

        ExternalCluster selectedCluster = savedCluster;
        for (ExternalDiscoveryReport report : selectedDiscoveryReports) {
            String agentId = report.getHostId() == null || report.getHostId().isBlank()
                    ? externalClusterDiscoveryService.discoveryHostId(report)
                    : report.getHostId();
            discoveryAgentRepository.findById(agentId)
                    .ifPresent(agent -> applyDiscoveryReportToNodes(selectedCluster, report, agent));
        }
        auditService.record(
                "CLUSTER_MANAGEMENT",
                "EXTERNAL_CLUSTER_CONNECTED",
                "CLUSTER",
                savedCluster.getId().toString(),
                savedCluster.getId(),
                "SUCCESS",
                null,
                null,
                null,
                externalAuditDetails(savedCluster)
        );
        return savedCluster;
    }


    @Transactional
    public Map<String, Object> recordDiscoveryReport(ExternalDiscoveryReport report) {
        validateDiscoveryReport(report);
        report.setLastSeen(OffsetDateTime.now().toString());
        externalClusterDiscoveryService.upsertDiscoveryAgent(report, null);

        String agentId = report.getHostId() == null || report.getHostId().isBlank() 
                ? externalClusterDiscoveryService.discoveryHostId(report) 
                : report.getHostId();
        io.translab.tantor.server.domain.DiscoveryAgent agent = discoveryAgentRepository.findById(agentId).orElse(null);

        Optional<ExternalCluster> connectedCluster = externalClusterQueryService.findExternalCluster(report.getKafkaClusterId(), report.getName(), report.getBootstrapServers().trim());

        if (connectedCluster.isPresent() && agent != null) {
            ExternalCluster cluster = upsertDiscoveryCluster(report);
            externalClusterDiscoveryService.linkDiscoveryAgent(agent, cluster);
            applyDiscoveryReportToNodes(cluster, report, agent);

            return Map.of(
                    "id", cluster.getId(),
                    "name", cluster.getName(),
                    "status", "registered",
                    "managementLevel", "AGENT_MANAGED"
            );
        }

        String key = ExternalClusterUtil.discoveryKey(report);
        externalClusterDiscoveryService.getPendingDiscoveries().put(key, report);
        return Map.of(
                "discoveryKey", key,
                "name", report.getName(),
                "status", "pending"
        );
    }


    @Transactional
    public ExternalCluster connectDiscovery(String discoveryKey) {
        Map<String, Object> inspection = externalClusterConnectionService.inspectDiscovery(discoveryKey);
        if (!Boolean.TRUE.equals(inspection.get("connected"))) {
            throw new IllegalArgumentException(String.valueOf(inspection.getOrDefault(
                    "message",
                    "The discovered Kafka bootstrap server is not reachable."
            )));
        }

        ExternalDiscoveryReport report = externalClusterConnectionService.requiredPendingDiscovery(discoveryKey);
        ExternalCluster cluster = upsertDiscoveryCluster(report);
        String agentId = report.getHostId() == null || report.getHostId().isBlank()
                ? externalClusterDiscoveryService.discoveryHostId(report)
                : report.getHostId();
        discoveryAgentRepository.findById(agentId).ifPresent(agent -> {
            externalClusterDiscoveryService.linkDiscoveryAgent(agent, cluster);
            applyDiscoveryReportToNodes(cluster, report, agent);
        });
        externalClusterDiscoveryService.getPendingDiscoveries().remove(discoveryKey);
        return cluster;
    }


    @Transactional
    public Optional<ExternalCluster> deleteExternalCluster(UUID id) {
        Optional<ExternalCluster> clusterOpt = externalClusterRepository.findById(id);
        if (clusterOpt.isEmpty()) {
            return Optional.empty();
        }

        ExternalCluster cluster = clusterOpt.get();
        cluster.setStatus("DELETED");
        cluster.setIsRunning(false);
        ExternalCluster saved = externalClusterRepository.save(cluster);

        externalClusterNodeRepository.deleteByClusterId(id);

        List<DiscoveryAgent> linkedAgents = discoveryAgentRepository.findByClusterId(id);
        for (DiscoveryAgent agent : linkedAgents) {
            agent.setClusterId(null);
        }
        discoveryAgentRepository.saveAll(linkedAgents);

        externalClusterDiscoveryService.getPendingDiscoveries().entrySet().removeIf(entry -> matchesExternalCluster(entry.getValue(), saved));

        return Optional.of(saved);
    }


    @Transactional
    public ExternalCluster upsertDiscoveryCluster(ExternalDiscoveryReport report) {
        validateDiscoveryReport(report);

        String bootstrap = report.getBootstrapServers().trim();
        ExternalCluster cluster = externalClusterQueryService.findExternalCluster(report.getKafkaClusterId(), report.getName(), bootstrap)
                .orElseGet(ExternalCluster::new);

        return saveDiscoveryCluster(report, bootstrap, cluster);
    }


    public List<Map<String, Object>> listExternalClusters() {
        return externalClusterRepository.findByStatusNot("DELETED").stream()
                .sorted(Comparator.comparing(ExternalCluster::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::toSummary)
                .toList();
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
        boolean isNew = cluster.getId() == null;
        cluster.setName(isNew ? report.getName().trim() : cluster.getName());
        cluster.setBootstrapServers(bootstrap);
        cluster.setKafkaClusterId(ExternalClusterUtil.blankToDefault(report.getKafkaClusterId(), null));
        cluster.setInstallPath(ExternalClusterUtil.blankToDefault(report.getInstallPath(), null));
        cluster.setLogDirs(ExternalClusterUtil.blankToDefault(report.getLogDirs(), null));
        cluster.setKafkaVersion(ExternalClusterUtil.blankToDefault(report.getKafkaVersion(), "Unknown"));
        cluster.setEnvironment(ExternalClusterUtil.blankToDefault(report.getEnvironment(), "unknown"));
        cluster.setBootstrapServers(externalClusterQueryService.mergeBootstrapServers(cluster.getBootstrapServers(), bootstrap));
        cluster.setStatus(report.isRunning() ? "SUCCESS" : "DEGRADED");
        cluster.setKafkaMode(ExternalClusterUtil.blankToDefault(report.getKafkaMode(), "KRaft"));
        cluster.setSecurity(ExternalClusterUtil.blankToDefault(report.getSecurity(), "PLAINTEXT"));
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

        if (isNew) {
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
                externalAuditDetails(saved)
            );
        }
        
        return saved;
    }


    private void applyDiscoveryReportToNodes(ExternalCluster cluster, ExternalDiscoveryReport report, DiscoveryAgent agent) {
        List<ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(cluster.getId());
        OffsetDateTime seen = OffsetDateTime.now();
        boolean matched = false;
        for (ExternalClusterNode node : nodes) {
            if (!externalClusterDiscoveryService.matchesDiscoveryNode(node, report, agent)) {
                continue;
            }
            matched = true;
            node.setCpuUsagePct(report.getCpuUsagePct());
            node.setMemoryUsedMb(report.getMemoryUsedMb());
            node.setMemoryTotalMb(report.getMemoryTotalMb());
            node.setDiskUsedGb(report.getDiskUsedGb());
            node.setDiskTotalGb(report.getDiskTotalGb());
            node.setLastSeen(seen);
            node.setInstallDir(ExternalClusterUtil.blankToDefault(report.getInstallPath(), node.getInstallDir()));
            node.setLogDirs(ExternalClusterUtil.blankToDefault(report.getLogDirs(), node.getLogDirs()));
            node.setConfigFile(ExternalClusterUtil.blankToDefault(report.getConfigFile(), node.getConfigFile()));
            node.setDataDirs(ExternalClusterUtil.blankToDefault(report.getDataDirs(), ExternalClusterUtil.blankToDefault(report.getLogDirs(), node.getDataDirs())));
            externalClusterNodeRepository.save(node);
        }

        if (!matched && report.getNodeId() != null) {
            ExternalClusterNode node = new ExternalClusterNode();
            node.setClusterId(cluster.getId());
            node.setHost(ExternalClusterUtil.firstNonBlank(ExternalClusterUtil.extractHostFromBootstrap(report.getBootstrapServers()), report.getHostname(), agent.getHostname()));
            node.setNodeId(report.getNodeId());
            String roles = ExternalClusterUtil.blankToDefault(report.getProcessRoles(), "").toLowerCase();
            node.setIsBroker(roles.isBlank() || roles.contains("broker"));
            node.setIsController(roles.contains("controller"));
            node.setCpuUsagePct(report.getCpuUsagePct());
            node.setMemoryUsedMb(report.getMemoryUsedMb());
            node.setMemoryTotalMb(report.getMemoryTotalMb());
            node.setDiskUsedGb(report.getDiskUsedGb());
            node.setDiskTotalGb(report.getDiskTotalGb());
            node.setLastSeen(seen);
            node.setInstallDir(ExternalClusterUtil.blankToDefault(report.getInstallPath(), null));
            node.setLogDirs(ExternalClusterUtil.blankToDefault(report.getLogDirs(), null));
            node.setConfigFile(ExternalClusterUtil.blankToDefault(report.getConfigFile(), null));
            node.setDataDirs(ExternalClusterUtil.blankToDefault(report.getDataDirs(), ExternalClusterUtil.blankToDefault(report.getLogDirs(), null)));
            externalClusterNodeRepository.save(node);
        }
    }


    private Map<String, Object> toSummary(ExternalCluster cluster) {
        List<ExternalBrokerRecord> brokers = externalClusterDiscoveryService.readBrokerRecords(cluster);
        List<DiscoveryAgent> agents = discoveryAgentRepository.findByClusterId(cluster.getId());
        long agentCount = agents.stream().filter(agent -> "ONLINE".equalsIgnoreCase(agent.getStatus())).count();
        long freshAgents = agents.stream().filter(externalClusterDiscoveryService::isFreshAgent).count();
        int brokerCount = cluster.getBrokerCount() != null ? cluster.getBrokerCount() : (brokers.isEmpty() ? 0 : brokers.size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", cluster.getId());
        summary.put("name", cluster.getName());
        summary.put("clusterId", ExternalClusterUtil.blankToDefault(cluster.getKafkaClusterId(), ""));
        summary.put("kafkaVersion", cluster.getKafkaVersion());
        summary.put("kafkaMode", ExternalClusterUtil.blankToDefault(cluster.getKafkaMode(), "Unknown"));
        summary.put("security", ExternalClusterUtil.blankToDefault(cluster.getSecurity(), "PLAINTEXT"));
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
        String brokerLastSeen = brokers.stream()
                .map(ExternalBrokerRecord::getLastSeen)
                .filter(value -> value != null && !value.isBlank())
                .max(String::compareTo)
                .orElse("");
        String agentLastSeen = agents.stream()
                .map(DiscoveryAgent::getLastHeartbeat)
                .filter(value -> value != null)
                .map(OffsetDateTime::toString)
                .max(String::compareTo)
                .orElse("");
        summary.put("lastSeen", brokerLastSeen.isBlank() ? agentLastSeen : brokerLastSeen);
        summary.put("installPath", ExternalClusterUtil.blankToDefault(cluster.getInstallPath(), ""));
        summary.put("logDirs", ExternalClusterUtil.blankToDefault(cluster.getLogDirs(), ""));
        return summary;
    }


    private boolean matchesExternalCluster(ExternalDiscoveryReport report, ExternalCluster cluster) {
        if (report == null || cluster == null) {
            return false;
        }
        return ExternalClusterUtil.safeEquals(report.getKafkaClusterId(), cluster.getKafkaClusterId())
                || ExternalClusterUtil.safeEquals(report.getName(), cluster.getName())
                || ExternalClusterUtil.safeEquals(report.getBootstrapServers(), cluster.getBootstrapServers());
    }


    private Map<String, Object> externalAuditDetails(ExternalCluster cluster) {
        List<ExternalBrokerRecord> brokers = externalClusterDiscoveryService.readBrokerRecords(cluster);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", cluster.getName());
        details.put("bootstrapServers", cluster.getBootstrapServers());
        details.put("kafkaVersion", ExternalClusterUtil.blankToDefault(cluster.getKafkaVersion(), "Unknown"));
        details.put("kafkaMode", ExternalClusterUtil.blankToDefault(cluster.getKafkaMode(), "Unknown"));
        details.put("environment", ExternalClusterUtil.blankToDefault(cluster.getEnvironment(), "unknown"));
        details.put("security", ExternalClusterUtil.blankToDefault(cluster.getSecurity(), "PLAINTEXT"));
        details.put("listeners", cluster.getListeners());
        details.put("advertisedListeners", cluster.getAdvertisedListeners());
        details.put("processRoles", cluster.getProcessRoles());
        details.put("externalBrokerHosts", brokers.stream().map(ExternalBrokerRecord::getHostname).toList());
        details.put("brokerCount", cluster.getBrokerCount());
        details.put("installPath", cluster.getInstallPath());
        details.put("logDirs", cluster.getLogDirs());
        details.put("cpuUsagePct", cluster.getCpuUsagePct());
        details.put("memoryUsedMb", cluster.getMemoryUsedMb());
        details.put("memoryTotalMb", cluster.getMemoryTotalMb());
        details.put("diskUsedGb", cluster.getDiskUsedGb());
        details.put("diskTotalGb", cluster.getDiskTotalGb());
        details.put("running", cluster.getIsRunning());
        return details;
    }

}
