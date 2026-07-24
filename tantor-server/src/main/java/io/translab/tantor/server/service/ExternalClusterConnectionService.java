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
public class ExternalClusterConnectionService {
    private final KafkaAdminService kafkaAdminService;
    private final io.translab.tantor.server.security.TruststoreStorageService truststoreStorageService;
    private final ExternalClusterDiscoveryService externalClusterDiscoveryService;
    private final io.translab.tantor.server.repository.DiscoveryAgentRepository discoveryAgentRepository;


    public Map<String, Object> testBootstrap(BootstrapExternalClusterRequest request) {
        String query = request.getBootstrapServers().trim();
        String queryHost = ExternalClusterUtil.extractHostFromBootstrap(query);

        Map<String, Object> result = new LinkedHashMap<>();
        boolean adminSuccess = false;
        
        try {
            // 1. PRIMARY: Try connecting directly via Kafka Admin API FIRST
            // Since cluster is not registered yet, we create a temporary DTO to pass properties
            ExternalCluster tempCluster = new ExternalCluster();
            tempCluster.setBootstrapServers(query);
            tempCluster.setSecurityProtocol(request.getSecurityProtocol());
            tempCluster.setSaslMechanism(request.getSaslMechanism());
            tempCluster.setSaslUsername(request.getSaslUsername());
            tempCluster.setSaslPasswordEncrypted(request.getSaslPassword()); // Plaintext during test
            tempCluster.setTruststoreType(request.getTruststoreType());
            tempCluster.setTruststorePasswordEncrypted(request.getTruststorePassword());
            tempCluster.setKeystoreType(request.getKeystoreType());
            tempCluster.setKeystorePasswordEncrypted(request.getKeystorePassword());
            tempCluster.setKeyPasswordEncrypted(request.getKeyPassword());
            tempCluster.setDisableHostnameVerification(Boolean.TRUE.equals(request.getDisableHostnameVerification()));
            
            // Handle temporary truststore file for test connection
            if (request.getTruststoreBase64() != null && !request.getTruststoreBase64().isBlank()) {
                String tempPath = truststoreStorageService.saveTruststore(UUID.randomUUID(), request.getTruststoreType(), request.getTruststoreBase64());
                tempCluster.setTruststorePath(tempPath);
            }
            if (request.getKeystoreBase64() != null && !request.getKeystoreBase64().isBlank()) {
                String tempPath = truststoreStorageService.saveTruststore(UUID.randomUUID(), "keystore_" + request.getKeystoreType(), request.getKeystoreBase64());
                tempCluster.setKeystorePath(tempPath);
            }

            try {
                Map<String, Object> adminData = kafkaAdminService.inspectBootstrapServers(tempCluster, false); // false = don't decrypt since we passed plaintext
                result.putAll(adminData);
                adminSuccess = true;
            } finally {
                // Clean up temporary files
                if (tempCluster.getTruststorePath() != null) {
                    try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(tempCluster.getTruststorePath())); } catch (Exception ignored) {}
                }
                if (tempCluster.getKeystorePath() != null) {
                    try { java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(tempCluster.getKeystorePath())); } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            // It failed. Don't throw yet, check if agent is enrolled as fallback.
            result.put("connected", false);
            result.put("message", e.getMessage());
        }

        // 2. Check if a Discovery Agent has already reported this cluster
        boolean agentFound = false;
        for (Map.Entry<String, ExternalDiscoveryReport> entry : externalClusterDiscoveryService.getPendingDiscoveries().entrySet()) {
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
                    result.put("kafkaVersion", ExternalClusterUtil.blankToDefault(report.getKafkaVersion(), "Unknown"));
                    result.put("kafka_version", ExternalClusterUtil.blankToDefault(report.getKafkaVersion(), "Unknown"));
                    result.put("kafkaMode", ExternalClusterUtil.blankToDefault(report.getKafkaMode(), "Unknown"));
                    result.put("mode", ExternalClusterUtil.blankToDefault(report.getKafkaMode(), "Unknown"));
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
                for (Map.Entry<String, ExternalDiscoveryReport> entry : externalClusterDiscoveryService.getPendingDiscoveries().entrySet()) {
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


    public Map<String, Object> inspectDiscovery(String discoveryKey) {
        ExternalDiscoveryReport report = requiredPendingDiscovery(discoveryKey);
        Map<String, Object> inspection = new LinkedHashMap<>();
        
        inspection.put("connected", true);
        inspection.put("success", true);
        inspection.put("status", "CONNECTED");

        externalClusterDiscoveryService.getPendingDiscoveries().put(discoveryKey, report);
        inspection.put("discoveryKey", discoveryKey);
        inspection.put("name", report.getName());
        inspection.put("hostname", report.getHostname());
        inspection.put("bootstrapServers", report.getBootstrapServers());
        inspection.put("bootstrap_servers", report.getBootstrapServers());
        inspection.put("kafkaVersion", ExternalClusterUtil.blankToDefault(report.getKafkaVersion(), "Unknown"));
        inspection.put("kafka_version", ExternalClusterUtil.blankToDefault(report.getKafkaVersion(), "Unknown"));
        inspection.put("kafkaMode", ExternalClusterUtil.blankToDefault(report.getKafkaMode(), "Unknown"));
        inspection.put("mode", ExternalClusterUtil.blankToDefault(report.getKafkaMode(), "Unknown"));
        inspection.put("kafkaClusterId", ExternalClusterUtil.blankToDefault(report.getKafkaClusterId(), ""));
        inspection.put("kafka_cluster_id", ExternalClusterUtil.blankToDefault(report.getKafkaClusterId(), ""));
        inspection.put("security", ExternalClusterUtil.blankToDefault(report.getSecurity(), "PLAINTEXT"));
        inspection.put("environment", ExternalClusterUtil.blankToDefault(report.getEnvironment(), "unknown"));
        inspection.put("installPath", ExternalClusterUtil.blankToDefault(report.getInstallPath(), ""));
        inspection.put("logDirs", ExternalClusterUtil.blankToDefault(report.getLogDirs(), ""));
        inspection.put("nodeId", report.getNodeId());
        inspection.put("lastSeen", report.getLastSeen());
        inspection.put("agentType", "KAFKA_DISCOVERY");
        return inspection;
    }


    public ExternalDiscoveryReport requiredPendingDiscovery(String discoveryKey) {
        ExternalDiscoveryReport report = externalClusterDiscoveryService.getPendingDiscoveries().get(discoveryKey);
        if (report == null || !externalClusterDiscoveryService.isFreshDiscovery(report)) {
            throw new IllegalArgumentException("No live discovery-agent report was found. Refresh after the agent reports again.");
        }
        return report;
    }

}
