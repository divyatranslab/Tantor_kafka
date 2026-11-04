package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.dto.PaginatedResponse;
import io.translab.tantor.server.dto.PartitionSummaryDto;
import io.translab.tantor.server.repository.ClusterRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PartitionCacheService {
    private static final long MIN_RETRY_INTERVAL_MILLIS = 1_000L;
    private final ClusterRepository clusterRepository;
    private final KafkaAdminService kafkaAdminService;
    private final Executor refreshExecutor;
    private final Map<UUID, CacheSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<UUID, MetadataSnapshot> metadataSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicLong> metadataVersions = new ConcurrentHashMap<>();
    private final Map<UUID, java.util.concurrent.atomic.AtomicLong> refreshGenerations = new ConcurrentHashMap<>();
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> nextRefreshNanos = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private volatile Set<UUID> eligibleClusterIds = Set.of();

    @Value("${tantor.partition-cache.cache-ttl-ms:30000}")
    private long cacheTtlMillis;
    @Value("${tantor.partition-cache.retention-ttl-ms:600000}")
    private long retentionTtlMillis;
    @Value("${tantor.partition-cache.refresh-timeout-ms:30000}")
    private long refreshTimeoutMillis;
    @Value("${tantor.partition-cache.metadata-ttl-ms:30000}")
    private long metadataTtlMillis;
    @Value("${tantor.partition-cache.batch-size:1000}")
    private int batchSize;
    @Value("${tantor.partition-cache.reconciliation-interval-ms:60000}")
    private long reconciliationIntervalMillis;
    @Value("${tantor.partition-cache.refresh-interval-ms:10000}")
    private long refreshIntervalMillis;

    public PartitionCacheService(ClusterRepository clusterRepository, KafkaAdminService kafkaAdminService,
            @Qualifier("kafkaCacheRefreshExecutor") Executor refreshExecutor) {
        this.clusterRepository = clusterRepository;
        this.kafkaAdminService = kafkaAdminService;
        this.refreshExecutor = refreshExecutor;
    }

    @Scheduled(fixedDelayString = "${tantor.partition-cache.refresh-interval-ms:10000}")
    public void refreshCaches() {
        scheduleDueRefreshes();
    }

    /** Reconciles cluster lifecycle and eventless Kafka changes on a slower cadence. */
    @Scheduled(fixedDelayString = "${tantor.partition-cache.reconciliation-interval-ms:60000}", initialDelay = 0)
    public synchronized void reconcileClusters() {
        final List<Cluster> clusters;
        try {
            clusters = clusterRepository.findByStatusNot("DELETED");
        } catch (Exception e) {
            log.warn("Unable to load clusters for partition refresh", e);
            return;
        }
        Set<UUID> eligible = clusters.stream().filter(this::isEligible).map(Cluster::getId).collect(Collectors.toSet());
        Set<UUID> previouslyEligible = eligibleClusterIds;
        eligibleClusterIds = Set.copyOf(eligible);
        previouslyEligible.stream().filter(clusterId -> !eligible.contains(clusterId)).forEach(this::invalidateCluster);
        long now = System.nanoTime();
        for (UUID clusterId : eligible) nextRefreshNanos.putIfAbsent(clusterId, staggeredDue(clusterId, now));
        nextRefreshNanos.keySet().removeIf(clusterId -> !eligible.contains(clusterId));
        snapshots.entrySet().removeIf(entry -> {
            boolean evict = !eligible.contains(entry.getKey()) || expired(entry.getValue(), retentionTtlMillis);
            if (eligible.contains(entry.getKey()) && evict) nextRefreshNanos.put(entry.getKey(), System.nanoTime());
            return evict;
        });
        metadataSnapshots.keySet().removeIf(clusterId -> !eligible.contains(clusterId)
                || expiredMetadata(metadataSnapshots.get(clusterId), retentionTtlMillis));
        scheduleDueRefreshes();
    }

    private void scheduleDueRefreshes() {
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        for (UUID clusterId : eligibleClusterIds) {
            CacheSnapshot snapshot = snapshots.get(clusterId);
            if (snapshot == null) {
                nextRefreshNanos.putIfAbsent(clusterId, nowNanos);
            } else if (snapshot.lastError == null && snapshot.lastSuccessfulRefreshMillis > 0
                    && nowMillis - snapshot.lastSuccessfulRefreshMillis > Math.max(1, cacheTtlMillis)) {
                nextRefreshNanos.compute(clusterId, (ignored, due) -> due == null ? nowNanos : Math.min(due, nowNanos));
            }
        }
        nextRefreshNanos.entrySet().stream()
                .filter(entry -> eligibleClusterIds.contains(entry.getKey()) && entry.getValue() <= nowNanos)
                .sorted(Map.Entry.<UUID, Long>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                .map(Map.Entry::getKey).toList().forEach(this::scheduleRefresh);
    }

    long retryDelayMillis(int failureCount) {
        long baseMillis = Math.max(MIN_RETRY_INTERVAL_MILLIS, refreshIntervalMillis);
        long capMillis = Math.max(baseMillis, reconciliationIntervalMillis);
        long delayMillis = baseMillis;
        for (int attempt = 1; attempt < Math.max(1, failureCount) && delayMillis < capMillis; attempt++) {
            delayMillis = delayMillis > capMillis / 2 ? capMillis : delayMillis * 2;
        }
        return Math.min(delayMillis, capMillis);
    }

    private long nextRetryDelayMillis(UUID clusterId) {
        int failures = consecutiveFailures.compute(clusterId,
                (ignored, current) -> current == null ? 1 : current == Integer.MAX_VALUE ? current : current + 1);
        return retryDelayMillis(failures);
    }

    private long staggeredDue(UUID clusterId, long nowNanos) {
        long spreadMillis = Math.max(1, reconciliationIntervalMillis);
        long phaseMillis = Long.remainderUnsigned(clusterId.hashCode(), spreadMillis);
        return nowNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(phaseMillis);
    }

    private boolean isEligible(Cluster cluster) {
        return cluster.getId() != null
                && ("SUCCESS".equalsIgnoreCase(cluster.getStatus()) || "EXTERNAL".equalsIgnoreCase(cluster.getMode()));
    }

    private void scheduleRefresh(UUID clusterId) {
        if (!inFlight.add(clusterId)) return;
        long generationBeforeRefresh = refreshGenerations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshClusterNow(clusterId, true);
                    if (eligibleClusterIds.contains(clusterId)
                            && refreshGenerations.get(clusterId).get() == generationBeforeRefresh + 1) {
                        consecutiveFailures.remove(clusterId);
                        nextRefreshNanos.put(clusterId, System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                                Math.max(1, Math.min(cacheTtlMillis, reconciliationIntervalMillis))));
                    }
                } catch (Exception e) {
                    if (eligibleClusterIds.contains(clusterId)
                            && refreshGenerations.get(clusterId).get() == generationBeforeRefresh + 1) {
                        nextRefreshNanos.put(clusterId, System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(nextRetryDelayMillis(clusterId)));
                    }
                    log.warn("Failed to refresh partition cache for cluster {}: {}", clusterId, e.getMessage());
                } finally {
                    inFlight.remove(clusterId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(clusterId);
            if (eligibleClusterIds.contains(clusterId)) {
                nextRefreshNanos.put(clusterId, System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(nextRetryDelayMillis(clusterId)));
            }
            recordFailure(clusterId, e);
            log.warn("Partition refresh queue is full; cluster {} will be retried on the next cycle", clusterId);
        }
    }

    void refreshClusterNow(UUID clusterId) throws Exception {
        refreshClusterNow(clusterId, false);
    }

    /** Invalidates topic metadata after a topic mutation so the next refresh sees the change. */
    public void invalidateMetadata(UUID clusterId) {
        consecutiveFailures.remove(clusterId);
        metadataVersions.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        refreshGenerations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        metadataSnapshots.remove(clusterId);
        nextRefreshNanos.put(clusterId, System.nanoTime());
    }

    private void invalidateCluster(UUID clusterId) {
        consecutiveFailures.remove(clusterId);
        refreshGenerations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        metadataVersions.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        snapshots.remove(clusterId);
        metadataSnapshots.remove(clusterId);
        nextRefreshNanos.remove(clusterId);
    }

    private boolean expiredMetadata(MetadataSnapshot snapshot, long retentionMillis) {
        return snapshot != null && System.currentTimeMillis() - snapshot.loadedAtMillis() > Math.max(1, retentionMillis);
    }

    private boolean expired(CacheSnapshot snapshot, long retentionMillis) {
        if (snapshot == null) return false;
        long lastObserved = snapshot.lastSuccessfulRefreshMillis > 0
                ? snapshot.lastSuccessfulRefreshMillis : snapshot.lastAttemptMillis;
        return System.currentTimeMillis() - lastObserved > Math.max(1, retentionMillis);
    }

    private void refreshClusterNow(UUID clusterId, boolean requireEligible) throws Exception {
        if (requireEligible && !eligibleClusterIds.contains(clusterId)) return;
        long generation = refreshGenerations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        long attemptedAt = System.currentTimeMillis();
        snapshots.compute(clusterId, (ignored, current) -> refreshGenerations.get(clusterId).get() != generation ? current
                : current == null ? CacheSnapshot.uninitialized(attemptedAt) : current.withAttempt(attemptedAt));
        long deadline = KafkaRefreshDeadline.afterMillis(refreshTimeoutMillis);
        try {
            CacheSnapshot refreshed = loadSnapshot(clusterId, deadline);
            if (refreshGenerations.get(clusterId).get() == generation
                    && (!requireEligible || eligibleClusterIds.contains(clusterId))) snapshots.put(clusterId, refreshed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(clusterId, e, generation);
            throw e;
        } catch (Exception e) {
            recordFailure(clusterId, e, generation);
            throw e;
        }
    }

    private CacheSnapshot loadSnapshot(UUID clusterId, long deadline) throws Exception {
        AdminClient client = kafkaAdminService.getAdminClient(clusterId);
        int timeout = KafkaRefreshDeadline.remainingMillis(deadline);
        MetadataSnapshot metadata = metadataSnapshots.get(clusterId);
        long metadataVersion = metadataVersions.getOrDefault(clusterId, new java.util.concurrent.atomic.AtomicLong()).get();
        if (metadata == null || System.currentTimeMillis() - metadata.loadedAtMillis() > Math.max(1, metadataTtlMillis)) {
            metadata = loadMetadata(client, deadline);
            if (metadataVersions.getOrDefault(clusterId, new java.util.concurrent.atomic.AtomicLong()).get() != metadataVersion) {
                throw new IllegalStateException("Topic metadata changed during refresh; retrying on next cycle");
            }
            MetadataSnapshot metadataToPublish = metadata;
            metadataSnapshots.compute(clusterId, (ignored, current) -> metadataVersions.getOrDefault(clusterId, new java.util.concurrent.atomic.AtomicLong()).get() == metadataVersion
                    ? metadataToPublish : current);
        }
        List<PartitionMetadata> partitionMetadata = metadata.partitions();
        if (partitionMetadata.isEmpty()) return CacheSnapshot.success(List.of(), System.currentTimeMillis());
        List<TopicPartition> partitions = partitionMetadata.stream()
                .map(item -> new TopicPartition(item.topicName(), item.partitionId())).toList();
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliestOffsets = new HashMap<>();
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = new HashMap<>();
        for (List<TopicPartition> batch : batches(partitions, batchSize > 0 ? batchSize : 1000)) {
            Map<TopicPartition, OffsetSpec> earliestRequest = batch.stream().collect(Collectors.toMap(
                    partition -> partition, ignored -> OffsetSpec.earliest(), (left, right) -> left, LinkedHashMap::new));
            Map<TopicPartition, OffsetSpec> latestRequest = batch.stream().collect(Collectors.toMap(
                    partition -> partition, ignored -> OffsetSpec.latest(), (left, right) -> left, LinkedHashMap::new));
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            earliestOffsets.putAll(KafkaRefreshDeadline.await(client.listOffsets(earliestRequest,
                    new ListOffsetsOptions().timeoutMs(timeout)).all(), deadline));
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            latestOffsets.putAll(KafkaRefreshDeadline.await(client.listOffsets(latestRequest,
                    new ListOffsetsOptions().timeoutMs(timeout)).all(), deadline));
        }
        if (earliestOffsets.size() != partitions.size() || latestOffsets.size() != partitions.size()) {
            throw new IllegalStateException("Kafka returned incomplete partition offsets");
        }

        List<PartitionSummaryDto> dtos = new ArrayList<>(partitions.size());
        for (PartitionMetadata item : partitionMetadata) {
            TopicPartition partition = new TopicPartition(item.topicName(), item.partitionId());
            long earliest = earliestOffsets.get(partition).offset();
            long latest = latestOffsets.get(partition).offset();
            boolean underReplicated = item.replicas().size() > item.isr().size();
            String health = item.leaderId() < 0 ? "CRITICAL" : (underReplicated ? "WARNING" : "HEALTHY");
            dtos.add(PartitionSummaryDto.builder().topicName(item.topicName()).partitionId(item.partitionId())
                    .leaderBroker(item.leaderId()).leaderHostname(item.leaderHostname())
                    .replicaBrokers(item.replicas()).isrBrokers(item.isr())
                    .earliestOffset(earliest).latestOffset(latest)
                    .messageCount(Math.max(0, latest - earliest)).underReplicated(underReplicated).health(health).build());
        }
        return CacheSnapshot.success(dtos, System.currentTimeMillis());
    }

    private MetadataSnapshot loadMetadata(AdminClient client, long deadline) throws Exception {
        int timeout = KafkaRefreshDeadline.remainingMillis(deadline);
        Set<String> topicNames = KafkaRefreshDeadline.await(
                client.listTopics(new ListTopicsOptions().listInternal(false).timeoutMs(timeout)).names(), deadline);
        List<PartitionMetadata> partitions = new ArrayList<>();
        for (List<String> batch : batches(new ArrayList<>(topicNames), batchSize > 0 ? batchSize : 1000)) {
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            Map<String, org.apache.kafka.clients.admin.TopicDescription> descriptions = KafkaRefreshDeadline.await(
                    client.describeTopics(batch, new DescribeTopicsOptions().timeoutMs(timeout)).allTopicNames(), deadline);
            if (!descriptions.keySet().containsAll(batch)) {
                throw new IllegalStateException("Kafka returned incomplete topic metadata");
            }
            for (Map.Entry<String, org.apache.kafka.clients.admin.TopicDescription> entry : descriptions.entrySet()) {
                for (TopicPartitionInfo info : entry.getValue().partitions()) {
                    partitions.add(new PartitionMetadata(entry.getKey(), info.partition(),
                            info.leader() == null ? -1 : info.leader().id(),
                            info.leader() == null ? "Unknown" : info.leader().host(),
                            info.replicas().stream().map(Node::id).toList(), info.isr().stream().map(Node::id).toList()));
                }
            }
        }
        return new MetadataSnapshot(List.copyOf(partitions), System.currentTimeMillis());
    }

    private void recordFailure(UUID clusterId, Exception exception) {
        recordFailure(clusterId, exception, refreshGenerations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get());
    }

    private void recordFailure(UUID clusterId, Exception exception, long generation) {
        long now = System.currentTimeMillis();
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        String error = message.length() > 300 ? message.substring(0, 300) : message;
        java.util.concurrent.atomic.AtomicLong currentGeneration = refreshGenerations.get(clusterId);
        if (currentGeneration == null || currentGeneration.get() != generation) return;
        snapshots.compute(clusterId, (ignored, current) -> currentGeneration.get() != generation ? current
                : current == null ? CacheSnapshot.failed(now, error) : current.withFailure(now, error));
    }

    public CacheRefreshStatus getCacheStatus(UUID clusterId) {
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        if (snapshot == null || snapshot.lastSuccessfulRefreshMillis == 0) {
            return new CacheRefreshStatus(snapshot != null && snapshot.lastError != null
                    ? CacheRefreshStatus.State.FAILED : CacheRefreshStatus.State.UNINITIALIZED,
                    0, snapshot == null ? 0 : snapshot.lastAttemptMillis, snapshot == null ? null : snapshot.lastError);
        }
        if (snapshot.lastError != null) {
            return new CacheRefreshStatus(CacheRefreshStatus.State.FAILED, snapshot.lastSuccessfulRefreshMillis,
                    snapshot.lastAttemptMillis, snapshot.lastError);
        }
        if (System.currentTimeMillis() - snapshot.lastSuccessfulRefreshMillis > Math.max(1, cacheTtlMillis)) {
            return new CacheRefreshStatus(CacheRefreshStatus.State.STALE, snapshot.lastSuccessfulRefreshMillis,
                    snapshot.lastAttemptMillis, snapshot.lastError);
        }
        return new CacheRefreshStatus(snapshot.partitions.isEmpty() ? CacheRefreshStatus.State.EMPTY : CacheRefreshStatus.State.FRESH,
                snapshot.lastSuccessfulRefreshMillis, snapshot.lastAttemptMillis, null);
    }

    public PaginatedResponse<PartitionSummaryDto> getPaginatedPartitions(
            UUID clusterId, int page, int size, String search, String sortBy) {
        if (page < 0 || size < 1) throw new IllegalArgumentException("page must be non-negative and size must be positive");
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        List<PartitionSummaryDto> filtered = (snapshot == null ? List.<PartitionSummaryDto>of() : snapshot.partitions).stream()
                .filter(partition -> search == null || search.isEmpty()
                        || partition.getTopicName().toLowerCase(java.util.Locale.ROOT).contains(search.toLowerCase(java.util.Locale.ROOT)))
                .map(PartitionCacheService::copy)
                .collect(Collectors.toCollection(ArrayList::new));
        filtered.sort(comparator(sortBy));
        int totalElements = filtered.size();
        int totalPages = totalElements == 0 ? 0 : (int) (((long) totalElements + size - 1L) / size);
        int normalizedPage = totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
        long startLong = (long) normalizedPage * size;
        int start = (int) Math.min(startLong, totalElements);
        int end = (int) Math.min(startLong + size, totalElements);
        List<PartitionSummaryDto> content = start < end ? List.copyOf(filtered.subList(start, end)) : List.of();
        return PaginatedResponse.<PartitionSummaryDto>builder().content(content).page(normalizedPage).size(size)
                .totalElements(totalElements).totalPages(totalPages).hasNext(normalizedPage < totalPages - 1).build();
    }

    private CacheSnapshot currentSnapshot(UUID clusterId) {
        CacheSnapshot snapshot = snapshots.get(clusterId);
        if (expired(snapshot, retentionTtlMillis)) {
            snapshots.remove(clusterId, snapshot);
            if (eligibleClusterIds.contains(clusterId)) {
                long now = System.nanoTime();
                nextRefreshNanos.compute(clusterId, (ignored, due) -> due == null ? now : Math.min(due, now));
            }
            return null;
        }
        return snapshot;
    }

    private Comparator<PartitionSummaryDto> comparator(String sortBy) {
        if ("partitionId".equalsIgnoreCase(sortBy)) return Comparator.comparingInt(PartitionSummaryDto::getPartitionId);
        if ("leaderBroker".equalsIgnoreCase(sortBy)) return Comparator.comparingInt(PartitionSummaryDto::getLeaderBroker);
        if ("messageCount".equalsIgnoreCase(sortBy)) return Comparator.comparingLong(PartitionSummaryDto::getMessageCount).reversed();
        if ("health".equalsIgnoreCase(sortBy)) return Comparator.comparing(PartitionSummaryDto::getHealth, String.CASE_INSENSITIVE_ORDER);
        return Comparator.comparing(PartitionSummaryDto::getTopicName, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(PartitionSummaryDto::getPartitionId);
    }

    private static PartitionSummaryDto copy(PartitionSummaryDto value) {
        return PartitionSummaryDto.builder().topicName(value.getTopicName()).partitionId(value.getPartitionId())
                .leaderBroker(value.getLeaderBroker()).leaderHostname(value.getLeaderHostname())
                .replicaBrokers(value.getReplicaBrokers() == null ? List.of() : List.copyOf(value.getReplicaBrokers()))
                .isrBrokers(value.getIsrBrokers() == null ? List.of() : List.copyOf(value.getIsrBrokers()))
                .earliestOffset(value.getEarliestOffset()).latestOffset(value.getLatestOffset())
                .messageCount(value.getMessageCount()).underReplicated(value.isUnderReplicated()).health(value.getHealth()).build();
    }

    private static <T> List<List<T>> batches(List<T> values, int size) {
        if (values.isEmpty()) return List.of();
        List<List<T>> result = new ArrayList<>((values.size() + size - 1) / size);
        for (int offset = 0; offset < values.size(); offset += size) {
            result.add(values.subList(offset, Math.min(values.size(), offset + size)));
        }
        return result;
    }

    private static final class CacheSnapshot {
        private final List<PartitionSummaryDto> partitions;
        private final long lastSuccessfulRefreshMillis;
        private final long lastAttemptMillis;
        private final String lastError;

        private CacheSnapshot(List<PartitionSummaryDto> partitions, long success, long attempt, String error) {
            this.partitions = List.copyOf(partitions);
            this.lastSuccessfulRefreshMillis = success;
            this.lastAttemptMillis = attempt;
            this.lastError = error;
        }

        private static CacheSnapshot success(List<PartitionSummaryDto> partitions, long refreshedAt) {
            return new CacheSnapshot(partitions, refreshedAt, refreshedAt, null);
        }

        private static CacheSnapshot uninitialized(long attemptedAt) {
            return new CacheSnapshot(List.of(), 0, attemptedAt, null);
        }

        private static CacheSnapshot failed(long attemptedAt, String error) {
            return new CacheSnapshot(List.of(), 0, attemptedAt, error);
        }

        private CacheSnapshot withAttempt(long attemptedAt) {
            return new CacheSnapshot(partitions, lastSuccessfulRefreshMillis, attemptedAt, lastError);
        }

        private CacheSnapshot withFailure(long attemptedAt, String error) {
            return new CacheSnapshot(partitions, lastSuccessfulRefreshMillis, attemptedAt, error);
        }
    }

    private record MetadataSnapshot(List<PartitionMetadata> partitions, long loadedAtMillis) {
        private MetadataSnapshot {
            partitions = List.copyOf(partitions);
        }
    }

    private record PartitionMetadata(String topicName, int partitionId, int leaderId, String leaderHostname,
            List<Integer> replicas, List<Integer> isr) {
        private PartitionMetadata {
            replicas = List.copyOf(replicas);
            isr = List.copyOf(isr);
        }
    }
}
