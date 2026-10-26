package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.dto.ConsumerGroupDetailDto;
import io.translab.tantor.server.dto.ConsumerGroupSummaryDto;
import io.translab.tantor.server.dto.MemberAssignmentDto;
import io.translab.tantor.server.dto.PaginatedResponse;
import io.translab.tantor.server.dto.PartitionLagDto;
import io.translab.tantor.server.repository.ClusterRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.DescribeConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec;
import org.apache.kafka.clients.admin.ListOffsetsOptions;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.admin.MemberDescription;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConsumerLagCacheService {

    private static final int DEFAULT_GROUP_BATCH_SIZE = 500;
    private static final int DEFAULT_PARTITION_BATCH_SIZE = 1000;
    private static final long MIN_RETRY_INTERVAL_MILLIS = 1_000L;
    private final ClusterRepository clusterRepository;
    private final KafkaAdminService kafkaAdminService;
    private final Executor refreshExecutor;
    private final Map<UUID, CacheSnapshot> snapshots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<UUID> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<UUID, java.util.concurrent.atomic.AtomicLong> generations = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> nextRefreshNanos = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Integer> consecutiveFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile Set<UUID> eligibleClusterIds = Set.of();

    @Value("${tantor.consumer-lag.cache-ttl-ms:30000}")
    private long cacheTtlMillis;
    @Value("${tantor.consumer-lag.retention-ttl-ms:600000}")
    private long retentionTtlMillis;
    @Value("${tantor.consumer-lag.refresh-timeout-ms:30000}")
    private long refreshTimeoutMillis;
    @Value("${tantor.consumer-lag.group-batch-size:500}")
    private int groupBatchSize;
    @Value("${tantor.consumer-lag.partition-batch-size:1000}")
    private int partitionBatchSize;
    @Value("${tantor.consumer-lag.reconciliation-interval-ms:60000}")
    private long reconciliationIntervalMillis;
    @Value("${tantor.consumer-lag.refresh-interval-ms:10000}")
    private long refreshIntervalMillis;

    public ConsumerLagCacheService(ClusterRepository clusterRepository, KafkaAdminService kafkaAdminService,
            @Qualifier("kafkaCacheRefreshExecutor") Executor refreshExecutor) {
        this.clusterRepository = clusterRepository;
        this.kafkaAdminService = kafkaAdminService;
        this.refreshExecutor = refreshExecutor;
    }

    @Scheduled(fixedDelayString = "${tantor.consumer-lag.refresh-interval-ms:10000}")
    public void refreshCaches() {
        scheduleDueRefreshes();
    }

    /** Reconciles cluster lifecycle and changes that have no reliable event trigger. */
    @Scheduled(fixedDelayString = "${tantor.consumer-lag.reconciliation-interval-ms:60000}", initialDelay = 0)
    public synchronized void reconcileClusters() {
        final List<Cluster> clusters;
        try {
            clusters = clusterRepository.findByStatusNot("DELETED");
        } catch (Exception e) {
            log.warn("Unable to load clusters for consumer-lag refresh", e);
            return;
        }

        Set<UUID> eligible = clusters.stream()
                .filter(this::isEligible)
                .map(Cluster::getId)
                .collect(Collectors.toSet());
        Set<UUID> previouslyEligible = eligibleClusterIds;
        eligibleClusterIds = Set.copyOf(eligible);
        previouslyEligible.stream().filter(clusterId -> !eligible.contains(clusterId)).forEach(this::invalidateCluster);
        long now = System.nanoTime();
        for (UUID clusterId : eligible) nextRefreshNanos.putIfAbsent(clusterId, staggeredDue(clusterId, now));
        nextRefreshNanos.keySet().removeIf(clusterId -> !eligible.contains(clusterId));
        snapshots.entrySet().removeIf(entry -> {
            boolean evict = !eligible.contains(entry.getKey()) || expired(entry.getValue(), retentionTtlMillis);
            if (eligible.contains(entry.getKey()) && evict) {
                nextRefreshNanos.put(entry.getKey(), System.nanoTime());
            }
            return evict;
        });
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
        return nowNanos + TimeUnit.MILLISECONDS.toNanos(phaseMillis);
    }

    private boolean isEligible(Cluster cluster) {
        return cluster.getId() != null
                && ("SUCCESS".equalsIgnoreCase(cluster.getStatus()) || "EXTERNAL".equalsIgnoreCase(cluster.getMode()));
    }

    private void scheduleRefresh(UUID clusterId) {
        if (!inFlight.add(clusterId)) return;
        long generationBeforeRefresh = generations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get();
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshClusterNow(clusterId, true);
                    if (eligibleClusterIds.contains(clusterId)
                            && generations.get(clusterId).get() == generationBeforeRefresh + 1) {
                        consecutiveFailures.remove(clusterId);
                        nextRefreshNanos.put(clusterId, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                                Math.max(1, Math.min(cacheTtlMillis, reconciliationIntervalMillis))));
                    }
                } catch (Exception e) {
                    if (eligibleClusterIds.contains(clusterId)
                            && generations.get(clusterId).get() == generationBeforeRefresh + 1) {
                        nextRefreshNanos.put(clusterId, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(nextRetryDelayMillis(clusterId)));
                    }
                    log.warn("Failed to refresh consumer-lag cache for cluster {}: {}", clusterId, e.getMessage());
                } finally {
                    inFlight.remove(clusterId);
                }
            });
        } catch (RejectedExecutionException e) {
            inFlight.remove(clusterId);
            if (eligibleClusterIds.contains(clusterId)) {
                nextRefreshNanos.put(clusterId, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(nextRetryDelayMillis(clusterId)));
            }
            recordFailure(clusterId, e);
            log.warn("Consumer-lag refresh queue is full; cluster {} will be retried on the next cycle", clusterId);
        }
    }

    void refreshClusterNow(UUID clusterId) throws Exception {
        refreshClusterNow(clusterId, false);
    }

    private void refreshClusterNow(UUID clusterId, boolean requireEligible) throws Exception {
        if (requireEligible && !eligibleClusterIds.contains(clusterId)) return;
        long generation = generations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        long attemptedAt = System.currentTimeMillis();
        snapshots.compute(clusterId, (ignored, current) -> generations.get(clusterId).get() != generation ? current
                : current == null ? CacheSnapshot.uninitialized(attemptedAt) : current.withAttempt(attemptedAt));
        long deadline = KafkaRefreshDeadline.afterMillis(refreshTimeoutMillis);
        try {
            CacheSnapshot refreshed = loadSnapshot(clusterId, deadline);
            if (generations.get(clusterId).get() == generation
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
        Collection<ConsumerGroupListing> listings = await(
                client.listConsumerGroups(new ListConsumerGroupsOptions().timeoutMs(timeout)).all(), deadline);
        List<String> groupIds = listings.stream().map(ConsumerGroupListing::groupId).distinct().toList();
        if (groupIds.isEmpty()) return CacheSnapshot.success(List.of(), Map.of(), System.currentTimeMillis());

        Map<String, ConsumerGroupDescription> descriptions = new HashMap<>();
        for (List<String> batch : batches(groupIds, positive(groupBatchSize, DEFAULT_GROUP_BATCH_SIZE))) {
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            descriptions.putAll(await(client.describeConsumerGroups(batch,
                    new DescribeConsumerGroupsOptions().timeoutMs(timeout)).all(), deadline));
        }
        if (descriptions.size() != groupIds.size() || !descriptions.keySet().containsAll(groupIds)) {
            throw new IllegalStateException("Kafka returned an incomplete consumer-group description set");
        }

        Map<String, Map<TopicPartition, OffsetAndMetadata>> groupOffsets = new HashMap<>();
        for (List<String> batch : batches(groupIds, positive(groupBatchSize, DEFAULT_GROUP_BATCH_SIZE))) {
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            Map<String, ListConsumerGroupOffsetsSpec> request = batch.stream().collect(Collectors.toMap(
                    groupId -> groupId, ignored -> new ListConsumerGroupOffsetsSpec(), (left, right) -> left, LinkedHashMap::new));
            Map<String, Map<TopicPartition, OffsetAndMetadata>> result = await(
                    client.listConsumerGroupOffsets(request, new ListConsumerGroupOffsetsOptions().timeoutMs(timeout)).all(), deadline);
            if (!result.keySet().containsAll(batch)) {
                throw new IllegalStateException("Kafka returned incomplete committed offsets for consumer groups");
            }
            groupOffsets.putAll(result);
        }

        Set<TopicPartition> allPartitions = new HashSet<>();
        groupOffsets.values().forEach(offsets -> allPartitions.addAll(offsets.keySet()));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets = new HashMap<>();
        List<TopicPartition> partitions = new ArrayList<>(allPartitions);
        for (List<TopicPartition> batch : batches(partitions, positive(partitionBatchSize, DEFAULT_PARTITION_BATCH_SIZE))) {
            timeout = KafkaRefreshDeadline.remainingMillis(deadline);
            Map<TopicPartition, OffsetSpec> request = batch.stream().collect(Collectors.toMap(
                    partition -> partition, ignored -> OffsetSpec.latest(), (left, right) -> left, LinkedHashMap::new));
            latestOffsets.putAll(await(client.listOffsets(request, new ListOffsetsOptions().timeoutMs(timeout)).all(), deadline));
        }
        if (latestOffsets.size() != allPartitions.size() || !latestOffsets.keySet().containsAll(allPartitions)) {
            throw new IllegalStateException("Kafka returned incomplete latest offsets for committed consumer partitions");
        }

        long now = System.currentTimeMillis();
        List<ConsumerGroupSummaryDto> summaries = new ArrayList<>(groupIds.size());
        Map<String, ConsumerGroupDetailDto> details = new LinkedHashMap<>();
        for (String groupId : groupIds) {
            ConsumerGroupDescription description = descriptions.get(groupId);
            Map<TopicPartition, OffsetAndMetadata> offsets = groupOffsets.get(groupId);
            long totalLag = 0;
            List<MemberAssignmentDto> members = new ArrayList<>();
            Set<TopicPartition> assignedPartitions = new HashSet<>();

            for (MemberDescription member : description.members()) {
                List<PartitionLagDto> memberPartitions = new ArrayList<>();
                for (TopicPartition partition : member.assignment().topicPartitions()) {
                    assignedPartitions.add(partition);
                    OffsetAndMetadata committed = offsets.get(partition);
                    ListOffsetsResult.ListOffsetsResultInfo end = latestOffsets.get(partition);
                    long currentOffset = committed == null ? -1 : committed.offset();
                    long logEndOffset = end == null ? -1 : end.offset();
                    long lag = currentOffset < 0 || logEndOffset < 0 ? -1 : Math.max(0, logEndOffset - currentOffset);
                    if (lag >= 0) totalLag = saturatedAdd(totalLag, lag);
                    memberPartitions.add(new PartitionLagDto(partition.topic(), partition.partition(), currentOffset, logEndOffset, lag));
                }
                members.add(new MemberAssignmentDto(member.consumerId(), member.clientId(), member.host(), List.copyOf(memberPartitions)));
            }

            List<PartitionLagDto> unassigned = new ArrayList<>();
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
                TopicPartition partition = entry.getKey();
                if (assignedPartitions.contains(partition)) continue;
                ListOffsetsResult.ListOffsetsResultInfo end = latestOffsets.get(partition);
                long logEndOffset = end == null ? -1 : end.offset();
                long lag = logEndOffset < 0 ? -1 : Math.max(0, logEndOffset - entry.getValue().offset());
                if (lag >= 0) totalLag = saturatedAdd(totalLag, lag);
                unassigned.add(new PartitionLagDto(partition.topic(), partition.partition(), entry.getValue().offset(), logEndOffset, lag));
            }
            if (!unassigned.isEmpty()) members.add(new MemberAssignmentDto("UNASSIGNED", "none", "none", List.copyOf(unassigned)));

            boolean inactive = description.state() == ConsumerGroupState.DEAD || description.state() == ConsumerGroupState.EMPTY;
            boolean incompleteAssignment = description.members().stream()
                    .flatMap(member -> member.assignment().topicPartitions().stream())
                    .anyMatch(partition -> !offsets.containsKey(partition) || !latestOffsets.containsKey(partition));
            String health = inactive ? "INACTIVE" : (incompleteAssignment ? "WARNING" : "HEALTHY");
            summaries.add(new ConsumerGroupSummaryDto(groupId, description.state().toString(),
                    description.members().size(), totalLag, health, now));
            details.put(groupId, new ConsumerGroupDetailDto(groupId, description.state().toString(), List.copyOf(members)));
        }
        return CacheSnapshot.success(summaries, details, now);
    }

    private <T> T await(KafkaFuture<T> future, long deadline) throws Exception {
        return KafkaRefreshDeadline.await(future, deadline);
    }

    private void recordFailure(UUID clusterId, Exception exception) {
        recordFailure(clusterId, exception, generations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).get());
    }

    private void recordFailure(UUID clusterId, Exception exception, long generation) {
        long now = System.currentTimeMillis();
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        if (message.length() > 300) message = message.substring(0, 300);
        final String error = message;
        java.util.concurrent.atomic.AtomicLong currentGeneration = generations.get(clusterId);
        if (currentGeneration == null || currentGeneration.get() != generation) return;
        snapshots.compute(clusterId, (ignored, current) -> currentGeneration.get() != generation ? current
                : current == null ? CacheSnapshot.failed(now, error) : current.withFailure(now, error));
    }

    private void invalidateCluster(UUID clusterId) {
        generations.computeIfAbsent(clusterId, ignored -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
        snapshots.remove(clusterId);
        nextRefreshNanos.remove(clusterId);
        consecutiveFailures.remove(clusterId);
    }

    private boolean expired(CacheSnapshot snapshot, long retentionMillis) {
        if (snapshot == null) return false;
        long lastObserved = snapshot.lastSuccessfulRefreshMillis > 0
                ? snapshot.lastSuccessfulRefreshMillis : snapshot.lastAttemptMillis;
        return System.currentTimeMillis() - lastObserved > Math.max(1, retentionMillis);
    }

    public CacheRefreshStatus getCacheStatus(UUID clusterId) {
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        if (snapshot == null || snapshot.lastSuccessfulRefreshMillis == 0) {
            return new CacheRefreshStatus(snapshot != null && snapshot.lastError != null
                    ? CacheRefreshStatus.State.FAILED : CacheRefreshStatus.State.UNINITIALIZED,
                    0, snapshot == null ? 0 : snapshot.lastAttemptMillis,
                    snapshot == null ? null : snapshot.lastError);
        }
        if (snapshot.lastError != null) {
            return new CacheRefreshStatus(CacheRefreshStatus.State.FAILED, snapshot.lastSuccessfulRefreshMillis,
                    snapshot.lastAttemptMillis, snapshot.lastError);
        }
        if (System.currentTimeMillis() - snapshot.lastSuccessfulRefreshMillis > Math.max(1, cacheTtlMillis)) {
            return new CacheRefreshStatus(CacheRefreshStatus.State.STALE, snapshot.lastSuccessfulRefreshMillis,
                    snapshot.lastAttemptMillis, snapshot.lastError);
        }
        return new CacheRefreshStatus(snapshot.summaries.isEmpty()
                ? CacheRefreshStatus.State.EMPTY : CacheRefreshStatus.State.FRESH,
                snapshot.lastSuccessfulRefreshMillis, snapshot.lastAttemptMillis, null);
    }

    private CacheSnapshot currentSnapshot(UUID clusterId) {
        CacheSnapshot snapshot = snapshots.get(clusterId);
        if (expired(snapshot, retentionTtlMillis)) {
            snapshots.remove(clusterId, snapshot);
            if (eligibleClusterIds.contains(clusterId)) {
                nextRefreshNanos.compute(clusterId, (ignored, due) -> due == null ? System.nanoTime() : Math.min(due, System.nanoTime()));
            }
            return null;
        }
        return snapshot;
    }

    public PaginatedResponse<ConsumerGroupSummaryDto> getPaginatedSummaries(
            UUID clusterId, int page, int size, String search, String sortBy) {
        if (page < 0 || size < 1) throw new IllegalArgumentException("page must be non-negative and size must be positive");
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        List<ConsumerGroupSummaryDto> filtered = (snapshot == null ? List.<ConsumerGroupSummaryDto>of() : snapshot.summaries).stream()
                .filter(group -> search == null || search.isEmpty()
                        || group.getGroupId().toLowerCase(java.util.Locale.ROOT).contains(search.toLowerCase(java.util.Locale.ROOT)))
                .map(ConsumerLagCacheService::copySummary)
                .collect(Collectors.toCollection(ArrayList::new));

        filtered.sort(comparator(sortBy));
        int totalElements = filtered.size();
        int totalPages = totalElements == 0 ? 0 : (int) (((long) totalElements + size - 1L) / size);
        int normalizedPage = totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
        long startLong = (long) normalizedPage * size;
        int start = (int) Math.min(startLong, totalElements);
        int end = (int) Math.min(startLong + size, totalElements);
        List<ConsumerGroupSummaryDto> paged = start < end ? List.copyOf(filtered.subList(start, end)) : List.of();

        return PaginatedResponse.<ConsumerGroupSummaryDto>builder()
                .content(paged)
                .page(normalizedPage)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .hasNext(normalizedPage < totalPages - 1)
                .build();
    }

    private Comparator<ConsumerGroupSummaryDto> comparator(String sortBy) {
        Comparator<ConsumerGroupSummaryDto> primary;
        if ("totalLag".equalsIgnoreCase(sortBy)) primary = Comparator.comparingLong(ConsumerGroupSummaryDto::getTotalLag).reversed();
        else if ("state".equalsIgnoreCase(sortBy)) primary = Comparator.comparing(ConsumerGroupSummaryDto::getState, String.CASE_INSENSITIVE_ORDER);
        else if ("health".equalsIgnoreCase(sortBy)) primary = Comparator.comparing(ConsumerGroupSummaryDto::getHealth, String.CASE_INSENSITIVE_ORDER);
        else if ("membersCount".equalsIgnoreCase(sortBy)) primary = Comparator.comparingInt(ConsumerGroupSummaryDto::getMembersCount).reversed();
        else primary = Comparator.comparing(ConsumerGroupSummaryDto::getGroupId, String.CASE_INSENSITIVE_ORDER);
        return primary.thenComparing(ConsumerGroupSummaryDto::getGroupId, String.CASE_INSENSITIVE_ORDER);
    }

    public ConsumerGroupDetailDto getDetail(UUID clusterId, String groupId) {
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        ConsumerGroupDetailDto detail = snapshot == null ? null : snapshot.details.get(groupId);
        return detail == null ? null : copyDetail(detail);
    }

    public List<ConsumerGroupSummaryDto> getSummaries(UUID clusterId) {
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        return snapshot == null ? List.of() : copySummaries(snapshot.summaries);
    }

    /** Returns a lag total from one atomically-read, sufficiently fresh, complete snapshot. */
    public OptionalLong getFreshTotalLag(UUID clusterId, long maxAgeMillis) {
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        if (snapshot == null || snapshot.lastSuccessfulRefreshMillis <= 0 || snapshot.lastError != null) return OptionalLong.empty();
        long maxAge = Math.min(Math.max(1, maxAgeMillis), Math.max(1, cacheTtlMillis));
        if (System.currentTimeMillis() - snapshot.lastSuccessfulRefreshMillis > maxAge) return OptionalLong.empty();
        long total = 0;
        for (ConsumerGroupSummaryDto summary : snapshot.summaries) {
            if (!"HEALTHY".equalsIgnoreCase(summary.getHealth()) || summary.getTotalLag() < 0) return OptionalLong.empty();
            long lag = summary.getTotalLag();
            total = lag > 0 && total > Long.MAX_VALUE - lag ? Long.MAX_VALUE : total + lag;
        }
        return OptionalLong.of(total);
    }

    public List<ConsumerGroupSummaryDto> getAlertSummaries(UUID clusterId) {
        CacheSnapshot snapshot = currentSnapshot(clusterId);
        return snapshot == null ? List.of() : copySummaries(snapshot.alertSummaries);
    }

    private static List<ConsumerGroupSummaryDto> copySummaries(List<ConsumerGroupSummaryDto> source) {
        return source.stream().map(ConsumerLagCacheService::copySummary).toList();
    }

    private static ConsumerGroupSummaryDto copySummary(ConsumerGroupSummaryDto value) {
        return new ConsumerGroupSummaryDto(value.getGroupId(), value.getState(), value.getMembersCount(),
                value.getTotalLag(), value.getHealth(), value.getLastUpdated());
    }

    private static ConsumerGroupDetailDto copyDetail(ConsumerGroupDetailDto value) {
        List<MemberAssignmentDto> members = value.getMembers().stream()
                .map(member -> new MemberAssignmentDto(member.getMemberId(), member.getClientId(), member.getHost(),
                        member.getPartitions().stream().map(partition -> new PartitionLagDto(partition.getTopic(),
                                partition.getPartition(), partition.getCurrentOffset(), partition.getLogEndOffset(),
                                partition.getLag())).toList()))
                .toList();
        return new ConsumerGroupDetailDto(value.getGroupId(), value.getState(), members);
    }

    private void recordFailure(UUID clusterId, Throwable exception) {
        recordFailure(clusterId, exception instanceof Exception e ? e : new RuntimeException(exception));
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static int positive(int configured, int fallback) {
        return configured > 0 ? configured : fallback;
    }

    private static <T> List<List<T>> batches(List<T> values, int batchSize) {
        if (values.isEmpty()) return List.of();
        List<List<T>> batches = new ArrayList<>((values.size() + batchSize - 1) / batchSize);
        for (int from = 0; from < values.size(); from += batchSize) {
            batches.add(values.subList(from, Math.min(values.size(), from + batchSize)));
        }
        return batches;
    }

    private static final class CacheSnapshot {
        private final List<ConsumerGroupSummaryDto> summaries;
        private final Map<String, ConsumerGroupDetailDto> details;
        private final List<ConsumerGroupSummaryDto> alertSummaries;
        private final long lastSuccessfulRefreshMillis;
        private final long lastAttemptMillis;
        private final String lastError;

        private CacheSnapshot(List<ConsumerGroupSummaryDto> summaries, Map<String, ConsumerGroupDetailDto> details,
                long lastSuccessfulRefreshMillis, long lastAttemptMillis, String lastError) {
            this.summaries = List.copyOf(summaries);
            this.details = Collections.unmodifiableMap(new LinkedHashMap<>(details));
            this.alertSummaries = this.summaries.stream()
                    .filter(group -> group.getTotalLag() >= 1000 || "WARNING".equalsIgnoreCase(group.getHealth()))
                    .toList();
            this.lastSuccessfulRefreshMillis = lastSuccessfulRefreshMillis;
            this.lastAttemptMillis = lastAttemptMillis;
            this.lastError = lastError;
        }

        private static CacheSnapshot success(List<ConsumerGroupSummaryDto> summaries,
                Map<String, ConsumerGroupDetailDto> details, long refreshedAt) {
            return new CacheSnapshot(summaries, details, refreshedAt, refreshedAt, null);
        }

        private static CacheSnapshot uninitialized(long attemptedAt) {
            return new CacheSnapshot(List.of(), Map.of(), 0, attemptedAt, null);
        }

        private static CacheSnapshot failed(long attemptedAt, String error) {
            return new CacheSnapshot(List.of(), Map.of(), 0, attemptedAt, error);
        }

        private CacheSnapshot withAttempt(long attemptedAt) {
            return new CacheSnapshot(summaries, details, lastSuccessfulRefreshMillis, attemptedAt, lastError);
        }

        private CacheSnapshot withFailure(long attemptedAt, String error) {
            return new CacheSnapshot(summaries, details, lastSuccessfulRefreshMillis, attemptedAt, error);
        }
    }
}
