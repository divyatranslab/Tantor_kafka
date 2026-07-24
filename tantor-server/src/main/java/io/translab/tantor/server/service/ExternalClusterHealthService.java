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
@lombok.extern.slf4j.Slf4j
public class ExternalClusterHealthService {
    public static final long AGENT_STALE_SECONDS = 180;
    private final io.translab.tantor.server.repository.ExternalClusterRepository externalClusterRepository;
    private final KafkaAdminService kafkaAdminService;
    private final ActivityAlertService activityAlertService;
    private final io.translab.tantor.server.repository.DiscoveryAgentRepository discoveryAgentRepository;
    private final ExternalClusterDiscoveryService externalClusterDiscoveryService;


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

}
