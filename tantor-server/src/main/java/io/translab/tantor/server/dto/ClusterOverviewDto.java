package io.translab.tantor.server.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClusterOverviewDto {
    private UUID clusterId;
    private String kafkaClusterId;
    private String name;
    private String kafkaVersion;
    private String controllerType;
    private OffsetDateTime generatedAt;
    private List<String> warnings;
    private UptimeSummary uptime;
    private PartitionSummary partitions;
    private List<BrokerRow> brokers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UptimeSummary {
        private int brokerCount;
        private Integer activeController;
        private String version;
        private String controllerType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionSummary {
        private int online;
        private int total;
        private int underReplicated;
        private int inSyncReplicas;
        private int totalReplicas;
        private int outOfSyncReplicas;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BrokerRow {
        private int brokerId;
        private String host;
        private int port;
        private String rack;
        private boolean controller;
        private long diskUsageBytes;
        private int logReplicaCount;
        private int inSyncReplicas;
        private int replicas;
        private Integer replicaSkewPct;
        private int leaders;
        private Integer leaderSkewPct;
    }
}
