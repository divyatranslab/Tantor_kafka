package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.dto.ClusterOverviewDto;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeLogDirsResult;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.LogDirDescription;
import org.apache.kafka.clients.admin.ReplicaInfo;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ClusterOverviewService {

    private final ClusterRepository clusterRepository;
    private final ExternalClusterRepository externalClusterRepository;
    private final KafkaAdminService kafkaAdminService;

    public ClusterOverviewDto getOverview(UUID clusterId) {
        String clusterName;
        String kafkaVersion;
        String originType;
        String mode;
        String installDirectory;
        String configDirectory;
        String dataDirectory;
        String logDirectory;
        
        var internalOpt = clusterRepository.findById(clusterId);
        if (internalOpt.isPresent()) {
            Cluster cluster = internalOpt.get();
            clusterName = cluster.getName();
            kafkaVersion = cluster.getKafkaVersion();
            originType = cluster.getOriginType();
            mode = cluster.getMode();
            installDirectory = cluster.getInstallDirectory();
            configDirectory = cluster.getConfigDirectory();
            dataDirectory = cluster.getDataDirectory();
            logDirectory = cluster.getLogDirectory();
        } else {
            ExternalCluster ext = externalClusterRepository.findById(clusterId)
                    .orElseThrow(() -> new IllegalArgumentException("Cluster not found"));
            clusterName = ext.getName();
            kafkaVersion = ext.getKafkaVersion();
            originType = "EXTERNAL";
            mode = ext.getKafkaMode();
            installDirectory = ext.getInstallPath();
            configDirectory = "";
            dataDirectory = "";
            logDirectory = ext.getLogDirs();
        }

        AdminClient client = kafkaAdminService.getAdminClient(clusterId);
        List<String> warnings = new ArrayList<>();

        try {
            DescribeClusterResult clusterResult = client.describeCluster();
            Collection<Node> nodes = clusterResult.nodes().get();
            Node controller = clusterResult.controller().get();
            String kafkaClusterId = clusterResult.clusterId().get();

            Map<Integer, BrokerAccumulator> brokerStats = new LinkedHashMap<>();
            nodes.stream()
                    .sorted(Comparator.comparingInt(Node::id))
                    .forEach(node -> brokerStats.put(node.id(), new BrokerAccumulator(node)));

            PartitionAccumulator partitionStats = collectPartitionStats(client, brokerStats, warnings);
            collectLogDirStats(client, brokerStats, warnings);

            int brokerCount = brokerStats.size();
            double avgReplicas = brokerCount == 0 ? 0 : (double) partitionStats.totalReplicas / brokerCount;
            double avgLeaders = brokerCount == 0 ? 0 : (double) partitionStats.totalPartitions / brokerCount;

            List<ClusterOverviewDto.BrokerRow> brokers = brokerStats.values().stream()
                    .map(stats -> stats.toDto(controller != null && stats.node.id() == controller.id(), avgReplicas, avgLeaders, brokerCount))
                    .toList();

            String controllerType = "zookeeper".equalsIgnoreCase(mode) ? "ZooKeeper" : "KRaft";
            return ClusterOverviewDto.builder()
                    .clusterId(clusterId)
                    .kafkaClusterId(kafkaClusterId)
                    .name(clusterName)
                    .kafkaVersion(kafkaVersion)
                    .controllerType(controllerType)
                    .originType(originType)
                    .installDirectory(installDirectory)
                    .configDirectory(configDirectory)
                    .dataDirectory(dataDirectory)
                    .logDirectory(logDirectory)
                    .generatedAt(OffsetDateTime.now())
                    .warnings(warnings)
                    .uptime(ClusterOverviewDto.UptimeSummary.builder()
                            .brokerCount(brokerCount)
                            .activeController(controller == null ? null : controller.id())
                            .version(kafkaVersion)
                            .controllerType(controllerType)
                            .build())
                    .partitions(ClusterOverviewDto.PartitionSummary.builder()
                            .online(partitionStats.onlinePartitions)
                            .total(partitionStats.totalPartitions)
                            .underReplicated(partitionStats.underReplicatedPartitions)
                            .inSyncReplicas(partitionStats.inSyncReplicas)
                            .totalReplicas(partitionStats.totalReplicas)
                            .outOfSyncReplicas(Math.max(0, partitionStats.totalReplicas - partitionStats.inSyncReplicas))
                            .build())
                    .brokers(brokers)
                    .build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kafkaAdminService.refreshAdminClient(clusterId);
            throw new RuntimeException("Interrupted while loading cluster overview");
        } catch (Exception e) {
            kafkaAdminService.refreshAdminClient(clusterId);
            throw new RuntimeException("Failed to load cluster overview: " + e.getMessage(), e);
        }
    }

    private PartitionAccumulator collectPartitionStats(
            AdminClient client,
            Map<Integer, BrokerAccumulator> brokerStats,
            List<String> warnings
    ) throws Exception {
        PartitionAccumulator totals = new PartitionAccumulator();
        Set<String> topicNames = client.listTopics(new ListTopicsOptions().listInternal(true)).names().get();
        if (topicNames.isEmpty()) {
            return totals;
        }

        DescribeTopicsResult describeTopicsResult = client.describeTopics(topicNames);
        Map<String, TopicDescription> topics = describeTopicsResult.allTopicNames().get();
        for (TopicDescription topic : topics.values()) {
            for (TopicPartitionInfo partition : topic.partitions()) {
                totals.totalPartitions++;
                if (partition.leader() != null && partition.leader().id() >= 0) {
                    totals.onlinePartitions++;
                    brokerStats.computeIfAbsent(partition.leader().id(), BrokerAccumulator::new).leaders++;
                }

                Set<Integer> isrIds = new HashSet<>();
                for (Node isrNode : partition.isr()) {
                    isrIds.add(isrNode.id());
                }

                totals.inSyncReplicas += isrIds.size();
                totals.totalReplicas += partition.replicas().size();
                if (partition.replicas().size() > isrIds.size()) {
                    totals.underReplicatedPartitions++;
                }

                for (Node replica : partition.replicas()) {
                    BrokerAccumulator broker = brokerStats.computeIfAbsent(replica.id(), BrokerAccumulator::new);
                    broker.replicas++;
                    if (isrIds.contains(replica.id())) {
                        broker.inSyncReplicas++;
                    }
                }
            }
        }
        return totals;
    }

    private void collectLogDirStats(
            AdminClient client,
            Map<Integer, BrokerAccumulator> brokerStats,
            List<String> warnings
    ) {
        if (brokerStats.isEmpty()) {
            return;
        }

        try {
            DescribeLogDirsResult result = client.describeLogDirs(brokerStats.keySet());
            for (Map.Entry<Integer, org.apache.kafka.common.KafkaFuture<Map<String, LogDirDescription>>> brokerEntry : result.descriptions().entrySet()) {
                BrokerAccumulator broker = brokerStats.get(brokerEntry.getKey());
                if (broker == null) {
                    continue;
                }

                Map<String, LogDirDescription> logDirs = brokerEntry.getValue().get();
                for (LogDirDescription logDir : logDirs.values()) {
                    if (logDir.error() != null) {
                        warnings.add("Log directory details are incomplete for broker " + brokerEntry.getKey() + ": " + logDir.error().getMessage());
                        continue;
                    }
                    for (ReplicaInfo replicaInfo : logDir.replicaInfos().values()) {
                        broker.diskUsageBytes += Math.max(0L, replicaInfo.size());
                        broker.logReplicaCount++;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load Kafka log directory details: {}", e.getMessage());
            warnings.add("Disk usage is unavailable because Kafka log directory details could not be loaded.");
        }
    }

    private String controllerType(Cluster cluster) {
        if ("zookeeper".equalsIgnoreCase(cluster.getMode())) {
            return "ZooKeeper";
        }
        return "KRaft";
    }

    private static Integer skewPct(int count, double average, int brokerCount) {
        if (brokerCount <= 1 || average <= 0) {
            return null;
        }
        return (int) Math.round(((count - average) / average) * 100.0);
    }

    private static class PartitionAccumulator {
        int onlinePartitions;
        int totalPartitions;
        int underReplicatedPartitions;
        int inSyncReplicas;
        int totalReplicas;
    }

    private static class BrokerAccumulator {
        final Node node;
        int inSyncReplicas;
        int replicas;
        int leaders;
        long diskUsageBytes;
        int logReplicaCount;

        BrokerAccumulator(Node node) {
            this.node = node;
        }

        BrokerAccumulator(int brokerId) {
            this.node = new Node(brokerId, "unknown", -1);
        }

        ClusterOverviewDto.BrokerRow toDto(boolean controller, double avgReplicas, double avgLeaders, int brokerCount) {
            return ClusterOverviewDto.BrokerRow.builder()
                    .brokerId(node.id())
                    .host(node.host())
                    .port(node.port())
                    .rack(node.rack() == null ? "" : node.rack())
                    .controller(controller)
                    .diskUsageBytes(diskUsageBytes)
                    .logReplicaCount(logReplicaCount)
                    .inSyncReplicas(inSyncReplicas)
                    .replicas(replicas)
                    .replicaSkewPct(skewPct(replicas, avgReplicas, brokerCount))
                    .leaders(leaders)
                    .leaderSkewPct(skewPct(leaders, avgLeaders, brokerCount))
                    .build();
        }
    }
}
