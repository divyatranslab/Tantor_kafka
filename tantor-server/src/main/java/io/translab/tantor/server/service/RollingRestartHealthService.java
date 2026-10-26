package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.repository.HostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class RollingRestartHealthService {
    private static final int GROUP_BATCH_SIZE = 500;
    private static final int PARTITION_BATCH_SIZE = 1000;
    private final KafkaAdminService kafkaAdminService;
    private final HostRepository hostRepository;
    private final Executor healthCheckExecutor;
    private final ConsumerLagCacheService consumerLagCacheService;

    public RollingRestartHealthService(KafkaAdminService kafkaAdminService, HostRepository hostRepository,
            @Qualifier("kafkaCacheRefreshExecutor") Executor healthCheckExecutor, ConsumerLagCacheService consumerLagCacheService) {
        this.kafkaAdminService = kafkaAdminService;
        this.hostRepository = hostRepository;
        this.healthCheckExecutor = healthCheckExecutor;
        this.consumerLagCacheService = consumerLagCacheService;
    }

    @Value("${tantor.rolling-restart.max-disk-used-percent:85}")
    private double maxDiskUsedPercent;
    @Value("${tantor.rolling-restart.max-consumer-lag:1000000}")
    private long maxConsumerLag;
    @Value("${tantor.rolling-restart.consumer-lag-cache-max-age-ms:10000}")
    private long consumerLagCacheMaxAgeMillis;
    @Value("${tantor.hosts.heartbeat-timeout-seconds:90}")
    private long heartbeatTimeoutSeconds;
    @Value("${tantor.rolling-restart.health-inspection-timeout-ms:30000}")
    private long defaultInspectionTimeoutMillis;

    public HealthSnapshot inspect(UUID clusterId, int expectedBrokerCount, Set<String> requiredHostIds) {
        return inspect(clusterId, expectedBrokerCount, requiredHostIds, defaultInspectionTimeoutMillis);
    }

    public HealthSnapshot inspect(UUID clusterId, int expectedBrokerCount, Set<String> requiredHostIds, long timeoutMillis) {
        long deadline = KafkaRefreshDeadline.afterMillis(timeoutMillis);
        Map<String, String> failures = new LinkedHashMap<>();
        int visibleBrokers = 0;
        int offlinePartitions = 0;
        int underReplicatedPartitions = 0;
        int minIsrViolations = 0;
        long consumerLag = 0;
        Integer controllerId = null;

        try {
            AdminClient client = kafkaAdminService.getAdminClient(clusterId);
            int timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            var cluster = client.describeCluster(new DescribeClusterOptions().timeoutMs(timeout));
            Collection<org.apache.kafka.common.Node> nodes = await(cluster.nodes(), deadline);
            org.apache.kafka.common.Node controller = await(cluster.controller(), deadline);
            visibleBrokers = nodes.size();
            controllerId = controller == null ? null : controller.id();
            if (visibleBrokers < expectedBrokerCount) {
                failures.put("brokerReachability", "Only " + visibleBrokers + " of " + expectedBrokerCount + " brokers are reachable");
            }
            if (controllerId == null || controllerId < 0) failures.put("controller", "Kafka has no active controller");

            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            Set<String> topicNames = await(client.listTopics(new ListTopicsOptions().listInternal(true).timeoutMs(timeout)).names(), deadline);
            Map<String, org.apache.kafka.clients.admin.TopicDescription> topics = new LinkedHashMap<>();
            List<String> names = new ArrayList<>(topicNames);
            for (List<String> batch : batchesStrings(names, GROUP_BATCH_SIZE)) {
                timeout = KafkaRefreshDeadline.remainingMillis(deadline);
                topics.putAll(await(client.describeTopics(batch, new DescribeTopicsOptions().timeoutMs(timeout)).allTopicNames(), deadline));
            }
            Map<String, Integer> minimumIsr = loadMinimumIsr(client, topicNames, deadline);
            for (org.apache.kafka.clients.admin.TopicDescription topic : topics.values()) {
                int requiredIsr = minimumIsr.getOrDefault(topic.name(), 1);
                for (var partition : topic.partitions()) {
                    if (partition.leader() == null || partition.leader().id() < 0) offlinePartitions++;
                    if (partition.isr().size() < partition.replicas().size()) underReplicatedPartitions++;
                    if (partition.isr().size() < requiredIsr) minIsrViolations++;
                }
            }
            if (offlinePartitions > 0) failures.put("offlinePartitions", offlinePartitions + " partition(s) have no leader");
            if (underReplicatedPartitions > 0) failures.put("underReplicatedPartitions", underReplicatedPartitions + " partition(s) are under-replicated");
            if (minIsrViolations > 0) failures.put("minimumIsr", minIsrViolations + " partition(s) are below min.insync.replicas");

            consumerLag = freshCachedConsumerLag(clusterId).orElseGet(() -> {
                try { return totalConsumerLag(client, deadline); }
                catch (Exception e) { throw new LagInspectionFailure(e); }
            });
            if (consumerLag > maxConsumerLag) {
                failures.put("consumerLag", "Total consumer lag " + consumerLag + " exceeds limit " + maxConsumerLag);
            }
        } catch (LagInspectionFailure e) {
            Throwable cause = e.getCause();
            if (cause instanceof InterruptedException) Thread.currentThread().interrupt();
            if (cause instanceof Exception exception) {
                log.warn("Rolling restart consumer-lag inspection failed for cluster {}", clusterId, exception);
                kafkaAdminService.refreshAdminClient(clusterId);
                failures.put("kafka", exception instanceof TimeoutException
                        ? "Kafka health inspection timed out"
                        : "Kafka health inspection failed: " + rootMessage(exception));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.put("kafka", "Health inspection was interrupted");
        } catch (Exception e) {
            log.warn("Rolling restart health inspection failed for cluster {}", clusterId, e);
            kafkaAdminService.refreshAdminClient(clusterId);
            failures.put("kafka", e instanceof TimeoutException
                    ? "Kafka health inspection timed out"
                    : "Kafka health inspection failed: " + rootMessage(e));
        }

        inspectHosts(requiredHostIds, failures, deadline);
        if (deadline - System.nanoTime() <= 0) failures.putIfAbsent("deadline", "Health inspection deadline exceeded");
        return new HealthSnapshot(failures.isEmpty(), visibleBrokers, controllerId, offlinePartitions,
                underReplicatedPartitions, minIsrViolations, consumerLag,
                Collections.unmodifiableMap(new LinkedHashMap<>(failures)));
    }

    private Map<String, Integer> loadMinimumIsr(AdminClient client, Set<String> topicNames, long deadline) throws Exception {
        if (topicNames.isEmpty()) return Map.of();
        Map<ConfigResource, String> resources = new LinkedHashMap<>();
        for (String name : topicNames) resources.put(new ConfigResource(ConfigResource.Type.TOPIC, name), name);
        int timeout = KafkaRefreshDeadline.remainingMillis(deadline);
        Map<ConfigResource, Config> configs = await(client.describeConfigs(resources.keySet(),
                new org.apache.kafka.clients.admin.DescribeConfigsOptions().timeoutMs(timeout)).all(), deadline);
        Map<String, Integer> result = new HashMap<>();
        for (Map.Entry<ConfigResource, Config> entry : configs.entrySet()) {
            ConfigEntry value = entry.getValue().get("min.insync.replicas");
            if (value != null && value.value() != null) result.put(resources.get(entry.getKey()), Integer.parseInt(value.value()));
        }
        return result;
    }

    OptionalLong freshCachedConsumerLag(UUID clusterId) {
        return consumerLagCacheService.getFreshTotalLag(clusterId, consumerLagCacheMaxAgeMillis);
    }

    private static final class LagInspectionFailure extends RuntimeException {
        private LagInspectionFailure(Exception cause) { super(cause); }
    }

    private long totalConsumerLag(AdminClient client, long deadline) throws Exception {
        int timeout = KafkaRefreshDeadline.remainingMillis(deadline);
        Collection<ConsumerGroupListing> listings = await(client.listConsumerGroups(
                new org.apache.kafka.clients.admin.ListConsumerGroupsOptions().timeoutMs(timeout)).all(), deadline);
        List<String> groupIds = listings.stream().map(ConsumerGroupListing::groupId).distinct().toList();
        if (groupIds.isEmpty()) return 0;

        Map<String, Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>> offsetsByGroup = new LinkedHashMap<>();
        for (List<String> batch : batchesStrings(groupIds, GROUP_BATCH_SIZE)) {
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            Map<String, ListConsumerGroupOffsetsSpec> request = new LinkedHashMap<>();
            for (String groupId : batch) request.put(groupId, new ListConsumerGroupOffsetsSpec());
            Map<String, Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>> result = await(
                    client.listConsumerGroupOffsets(request, new ListConsumerGroupOffsetsOptions().timeoutMs(timeout)).all(), deadline);
            if (!result.keySet().containsAll(batch)) throw new IllegalStateException("Kafka omitted consumer group offsets");
            offsetsByGroup.putAll(result);
        }

        Set<TopicPartition> partitions = offsetsByGroup.values().stream()
                .flatMap(groupOffsets -> groupOffsets.keySet().stream()).collect(java.util.stream.Collectors.toSet());
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest = new HashMap<>();
        for (List<TopicPartition> batch : batchesPartitions(new ArrayList<>(partitions), PARTITION_BATCH_SIZE)) {
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            Map<TopicPartition, OffsetSpec> request = new LinkedHashMap<>();
            for (TopicPartition partition : batch) request.put(partition, OffsetSpec.latest());
            latest.putAll(await(client.listOffsets(request, new ListOffsetsOptions().timeoutMs(timeout)).all(), deadline));
        }
        if (latest.size() != partitions.size()) throw new IllegalStateException("Kafka omitted latest offsets for committed partitions");

        long total = 0;
        for (Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> groupOffsets : offsetsByGroup.values()) {
            for (Map.Entry<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> offset : groupOffsets.entrySet()) {
                long lag = Math.max(0, latest.get(offset.getKey()).offset() - offset.getValue().offset());
                total = lag > 0 && total > Long.MAX_VALUE - lag ? Long.MAX_VALUE : total + lag;
            }
        }
        return total;
    }

    private <T> T await(KafkaFuture<T> future, long deadline) throws Exception {
        return KafkaRefreshDeadline.await(future, deadline);
    }

    /**
     * The deadline bounds how long inspection waits for the repository call. Future.cancel(true)
     * is best effort; JDBC/provider calls may continue until their own query or connection timeout.
     */
    private void inspectHosts(Set<String> requiredHostIds, Map<String, String> failures, long deadline) {
        if (requiredHostIds == null || requiredHostIds.isEmpty()) return;
        if (deadline - System.nanoTime() <= 0) {
            failures.putIfAbsent("deadline", "Health inspection deadline exceeded before host health could be checked");
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusSeconds(heartbeatTimeoutSeconds);
        Map<String, Host> hosts = new HashMap<>();
        CompletableFuture<List<Host>> hostLookup = null;
        try {
            hostLookup = CompletableFuture.supplyAsync(
                    () -> hostRepository.findAllById(requiredHostIds), healthCheckExecutor);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException("Health inspection deadline exceeded before host lookup");
            hostLookup.get(remaining, TimeUnit.NANOSECONDS).forEach(host -> hosts.put(host.getId(), host));
        } catch (TimeoutException e) {
            if (hostLookup != null) hostLookup.cancel(true);
            failures.putIfAbsent("deadline", "Health inspection deadline exceeded during host lookup");
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failures.putIfAbsent("hostReachability", "Host health lookup was interrupted");
            return;
        } catch (ExecutionException | java.util.concurrent.RejectedExecutionException e) {
            failures.putIfAbsent("hostReachability", "Host health lookup failed: " + rootMessage(e));
            return;
        }
        List<String> unavailable = new ArrayList<>();
        List<String> diskPressure = new ArrayList<>();
        for (String hostId : requiredHostIds) {
            Host host = hosts.get(hostId);
            if (host == null || host.getLastHeartbeat() == null || host.getLastHeartbeat().isBefore(cutoff)
                    || !"ONLINE".equalsIgnoreCase(host.getStatus())) {
                unavailable.add(hostId);
                continue;
            }
            if (host.getDiskTotalGb() != null && host.getDiskTotalGb() > 0 && host.getDiskUsedGb() != null) {
                double usedPercent = (host.getDiskUsedGb() * 100.0) / host.getDiskTotalGb();
                if (usedPercent >= maxDiskUsedPercent) diskPressure.add(hostId + " (" + Math.round(usedPercent) + "%)");
            }
        }
        if (deadline - System.nanoTime() <= 0) failures.putIfAbsent("deadline", "Health inspection deadline exceeded during host checks");
        if (!unavailable.isEmpty()) failures.put("hostReachability", "Agent heartbeat missing or stale for: " + String.join(", ", unavailable));
        if (!diskPressure.isEmpty()) failures.put("disk", "Disk usage is above the safe limit on: " + String.join(", ", diskPressure));
    }

    private List<List<String>> batchesStrings(List<String> values, int size) {
        if (values.isEmpty()) return List.of();
        List<List<String>> result = new ArrayList<>((values.size() + size - 1) / size);
        for (int offset = 0; offset < values.size(); offset += size) {
            result.add(values.subList(offset, Math.min(values.size(), offset + size)));
        }
        return result;
    }

    private List<List<TopicPartition>> batchesPartitions(List<TopicPartition> values, int size) {
        if (values.isEmpty()) return List.of();
        List<List<TopicPartition>> result = new ArrayList<>((values.size() + size - 1) / size);
        for (int offset = 0; offset < values.size(); offset += size) {
            result.add(values.subList(offset, Math.min(values.size(), offset + size)));
        }
        return result;
    }

    private String rootMessage(Exception exception) {
        Throwable current = exception;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record HealthSnapshot(boolean healthy, int visibleBrokers, Integer controllerId,
            int offlinePartitions, int underReplicatedPartitions, int minIsrViolations,
            long consumerLag, Map<String, String> failures) {
        public String failureReason() {
            return failures.values().stream().collect(java.util.stream.Collectors.joining("; "));
        }
    }
}
