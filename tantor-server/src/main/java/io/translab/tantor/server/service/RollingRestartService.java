package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class RollingRestartService {

    private final ClusterRepository clusterRepository;
    private final DeploymentService deploymentService;
    private final KafkaAdminService kafkaAdminService;

    // A simple in-memory tracker of restart task status.
    // Map of TaskID -> Status Message
    private final Map<String, String> restartTasks = new ConcurrentHashMap<>();

    @Async
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void executeRollingRestart(UUID clusterId, String taskId) {
        restartTasks.put(taskId, "Starting rolling restart for cluster " + clusterId);
        
        try {
            Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
            if (cluster == null) {
                restartTasks.put(taskId, "FAILED: Cluster not found");
                return;
            }

        List<ClusterServiceAssignment> brokers = cluster.getServices().stream()
                .filter(svc -> "broker".equals(svc.getRole()) || "broker_controller".equals(svc.getRole()))
                .toList();

        if (brokers.isEmpty()) {
            restartTasks.put(taskId, "FAILED: No brokers found in cluster");
            return;
        }

        for (int i = 0; i < brokers.size(); i++) {
            ClusterServiceAssignment broker = brokers.get(i);
            String msgPrefix = String.format("Node %d/%d (%s): ", i + 1, brokers.size(), broker.getHostId());
            
            restartTasks.put(taskId, msgPrefix + "Dispatching restart command to agent");
            deploymentService.restartService(clusterId, broker.getHostId(), "kafka");

            // Give it a few seconds to actually stop and start restarting
            Thread.sleep(10000);

                restartTasks.put(taskId, msgPrefix + "Waiting for broker to rejoin cluster...");
                waitForBrokerHealth(clusterId);

                restartTasks.put(taskId, msgPrefix + "Restart complete & healthy.");
                // Small delay between nodes
                Thread.sleep(5000);
            }
            
            restartTasks.put(taskId, "COMPLETED successfully.");
        } catch (Exception e) {
            log.error("Rolling restart failed", e);
            restartTasks.put(taskId, "FAILED: " + e.getMessage());
        }
    }

    private void waitForBrokerHealth(UUID clusterId) throws InterruptedException {
        int maxRetries = 30; // 30 * 10s = 5 minutes
        for (int i = 0; i < maxRetries; i++) {
            try {
                List<Map<String, Object>> topics = kafkaAdminService.listTopics(clusterId);
                long underReplicatedTotal = topics.stream()
                        .mapToLong(t -> (long) t.get("underReplicated"))
                        .sum();

                if (underReplicatedTotal == 0) {
                    log.info("Cluster {} is healthy. 0 under-replicated partitions.", clusterId);
                    return; // Healthy!
                } else {
                    log.info("Cluster {} has {} under-replicated partitions. Waiting...", clusterId, underReplicatedTotal);
                }
            } catch (Exception e) {
                log.warn("Failed to check health for cluster {}, maybe broker is down: {}", clusterId, e.getMessage());
                // Force a refresh of the AdminClient in case the connection died
                kafkaAdminService.refreshAdminClient(clusterId);
            }
            Thread.sleep(10000);
        }
        throw new RuntimeException("Timeout waiting for cluster to become healthy after restart");
    }

    public String getTaskStatus(String taskId) {
        return restartTasks.getOrDefault(taskId, "NOT_FOUND");
    }
}
