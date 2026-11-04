package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.repository.ClusterRepository;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsumerLagCacheServiceTest {

    @Test
    void distinguishesUninitializedFromSuccessfullyEmptyAndMarksExpiredSnapshotStale() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        ListConsumerGroupsResult emptyGroups = listResult(KafkaFuture.completedFuture(List.of()));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(emptyGroups);

        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
        fixture.service.refreshClusterNow(clusterId);
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.EMPTY);
        assertThat(fixture.service.getSummaries(clusterId)).isEmpty();

        ReflectionTestUtils.setField(fixture.service, "cacheTtlMillis", -1L);
        ReflectionTestUtils.setField(fixture.service, "retentionTtlMillis", Long.MAX_VALUE);
        Object snapshot = ((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.STALE);
        assertThat(fixture.service.getSummaries(clusterId)).isEmpty();
        ReflectionTestUtils.setField(fixture.service, "cacheTtlMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(fixture.service, "retentionTtlMillis", -1L);
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
        when(fixture.clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of());
        fixture.service.reconcileClusters();
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
    }

    @Test
    void successfulRefreshAfterFailureClearsErrorAndRestoresFreshStatus() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        KafkaFutureImpl<Collection<ConsumerGroupListing>> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new IllegalStateException("temporary broker error"));
        ListConsumerGroupsResult failedResult = listResult(failed);
        ListConsumerGroupsResult recoveredResult = listResult(KafkaFuture.completedFuture(List.of()));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class)))
                .thenReturn(failedResult, recoveredResult);
        try { fixture.service.refreshClusterNow(clusterId); } catch (Exception expected) { }
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.FAILED);

        fixture.service.refreshClusterNow(clusterId);

        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.EMPTY);
        assertThat(fixture.service.getCacheStatus(clusterId).lastError()).isNull();
        assertThat(fixture.service.getCacheStatus(clusterId).lastSuccessfulRefreshMillis()).isPositive();
    }

    @Test
    void queuedRefreshCannotRecreateCacheAfterClusterBecomesIneligible() {
        List<Runnable> queued = new ArrayList<>();
        Fixture fixture = new Fixture(queued::add);
        UUID clusterId = UUID.randomUUID();
        Cluster eligible = new Cluster();
        eligible.setId(clusterId);
        eligible.setStatus("SUCCESS");
        when(fixture.clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(eligible)).thenReturn(List.of());

        fixture.service.reconcileClusters();
        fixture.service.reconcileClusters();
        queued.getFirst().run();

        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
        verify(fixture.kafkaAdminService, never()).getAdminClient(clusterId);
    }

    @Test
    void batchesThousandConsumerGroupsAndPublishesOnlyAfterAllBatchesSucceed() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        List<ConsumerGroupListing> listings = new ArrayList<>();
        Map<String, ConsumerGroupDescription> descriptions = new LinkedHashMap<>();
        for (int i = 0; i < 1001; i++) {
            String id = "group-" + i;
            ConsumerGroupListing listing = mock(ConsumerGroupListing.class);
            when(listing.groupId()).thenReturn(id);
            listings.add(listing);
            descriptions.put(id, description(id));
        }
        ListConsumerGroupsResult groupResult = listResult(KafkaFuture.completedFuture(listings));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(groupResult);
        when(fixture.admin.describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class)))
                .thenAnswer(invocation -> {
                    Collection<String> ids = invocation.getArgument(0);
                    Map<String, ConsumerGroupDescription> batch = new LinkedHashMap<>();
                    ids.forEach(id -> batch.put(id, descriptions.get(id)));
                    return describeResult(KafkaFuture.completedFuture(batch));
                });
        when(fixture.admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class)))
                .thenAnswer(invocation -> {
                    Map<String, ListConsumerGroupOffsetsSpec> batch = invocation.getArgument(0);
                    Map<String, Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>> offsets = new LinkedHashMap<>();
                    batch.keySet().forEach(id -> offsets.put(id, Map.of()));
                    return offsetsResult(KafkaFuture.completedFuture(offsets));
                });

        fixture.service.refreshClusterNow(clusterId);

        assertThat(fixture.service.getSummaries(clusterId)).hasSize(1001);
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.FRESH);
        verify(fixture.admin, times(3)).describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class));
        verify(fixture.admin, times(3)).listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class));
        verify(fixture.admin, never()).listOffsets(anyMap(), any(ListOffsetsOptions.class));
    }

    @Test
    void failedRefreshPreservesLastGoodSnapshotAndReportsFailure() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        ConsumerGroupListing listing = mock(ConsumerGroupListing.class);
        when(listing.groupId()).thenReturn("group-1");
        ConsumerGroupDescription description = description("group-1");
        KafkaFutureImpl<Map<String, Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>>> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new IllegalStateException("offset lookup failed"));
        ListConsumerGroupOffsetsResult failedOffsets = offsetsResult(failed);
        when(fixture.admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(failedOffsets);

        // First publish a valid group snapshot, then fail a later refresh.
        ListConsumerGroupsResult groupResult = listResult(KafkaFuture.completedFuture(List.of(listing)));
        DescribeConsumerGroupsResult describeResult = describeResult(KafkaFuture.completedFuture(Map.of("group-1", description)));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(groupResult);
        when(fixture.admin.describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class))).thenReturn(describeResult);
        ListConsumerGroupOffsetsResult goodOffsets = offsetsResult(KafkaFuture.completedFuture(Map.of("group-1", Map.of())));
        when(fixture.admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(goodOffsets);
        fixture.service.refreshClusterNow(clusterId);
        assertThat(fixture.service.getSummaries(clusterId)).hasSize(1);

        when(fixture.admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(failedOffsets);
        try {
            fixture.service.refreshClusterNow(clusterId);
        } catch (Exception expected) {
            // The caller observes failure; the previously published snapshot remains available.
        }

        assertThat(fixture.service.getSummaries(clusterId)).extracting(summary -> summary.getGroupId()).containsExactly("group-1");
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.FAILED);
        assertThat(fixture.service.getCacheStatus(clusterId).lastError()).contains("offset lookup failed");
    }

    @Test
    void batchesThousandsOfPartitionsAndFetchesEachLatestOffsetOnlyOnce() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        ConsumerGroupListing listing = mock(ConsumerGroupListing.class);
        when(listing.groupId()).thenReturn("group-1");
        MemberDescription member = mock(MemberDescription.class);
        MemberAssignment assignment = mock(MemberAssignment.class);
        List<TopicPartition> partitions = new ArrayList<>();
        Map<TopicPartition, OffsetAndMetadata> committed = new LinkedHashMap<>();
        for (int i = 0; i < 2001; i++) {
            TopicPartition partition = new TopicPartition("topic", i);
            partitions.add(partition);
            committed.put(partition, new OffsetAndMetadata(1));
        }
        when(member.assignment()).thenReturn(assignment);
        when(assignment.topicPartitions()).thenReturn(new LinkedHashSet<>(partitions));
        ConsumerGroupDescription description = mock(ConsumerGroupDescription.class);
        when(description.members()).thenReturn(List.of(member));
        when(description.state()).thenReturn(ConsumerGroupState.STABLE);
        ListOffsetsResult.ListOffsetsResultInfo offsetInfo = mock(ListOffsetsResult.ListOffsetsResultInfo.class);
        when(offsetInfo.offset()).thenReturn(2L);

        ListConsumerGroupsResult groupResult = listResult(KafkaFuture.completedFuture(List.of(listing)));
        DescribeConsumerGroupsResult describeResult = describeResult(KafkaFuture.completedFuture(Map.of("group-1", description)));
        ListConsumerGroupOffsetsResult groupOffsets = offsetsResult(KafkaFuture.completedFuture(Map.of("group-1", committed)));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(groupResult);
        when(fixture.admin.describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class))).thenReturn(describeResult);
        when(fixture.admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(groupOffsets);
        when(fixture.admin.listOffsets(anyMap(), any(ListOffsetsOptions.class))).thenAnswer(invocation -> {
            Map<TopicPartition, OffsetSpec> request = invocation.getArgument(0);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> offsets = new LinkedHashMap<>();
            request.keySet().forEach(partition -> offsets.put(partition, offsetInfo));
            return listOffsetsResult(KafkaFuture.completedFuture(offsets));
        });

        fixture.service.refreshClusterNow(clusterId);

        assertThat(fixture.service.getSummaries(clusterId).getFirst().getTotalLag()).isEqualTo(2001);
        verify(fixture.admin, times(3)).listOffsets(anyMap(), any(ListOffsetsOptions.class));
    }

    @Test
    void inactiveHealthTakesPrecedenceOverMissingAssignmentWarning() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        ConsumerGroupListing listing = mock(ConsumerGroupListing.class);
        when(listing.groupId()).thenReturn("dead-group");
        TopicPartition assigned = new TopicPartition("topic", 0);
        MemberDescription member = mock(MemberDescription.class);
        MemberAssignment assignment = mock(MemberAssignment.class);
        when(member.assignment()).thenReturn(assignment);
        when(assignment.topicPartitions()).thenReturn(Set.of(assigned));
        ConsumerGroupDescription description = mock(ConsumerGroupDescription.class);
        when(description.members()).thenReturn(List.of(member));
        when(description.state()).thenReturn(ConsumerGroupState.DEAD);
        ListConsumerGroupsResult groupResult = listResult(KafkaFuture.completedFuture(List.of(listing)));
        DescribeConsumerGroupsResult describeResult = describeResult(KafkaFuture.completedFuture(Map.of("dead-group", description)));
        ListConsumerGroupOffsetsResult offsetResult = offsetsResult(KafkaFuture.completedFuture(Map.of("dead-group", Map.of())));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(groupResult);
        when(fixture.admin.describeConsumerGroups(anyCollection(), any(DescribeConsumerGroupsOptions.class))).thenReturn(describeResult);
        when(fixture.admin.listConsumerGroupOffsets(anyMap(), any(ListConsumerGroupOffsetsOptions.class))).thenReturn(offsetResult);

        fixture.service.refreshClusterNow(clusterId);

        assertThat(fixture.service.getSummaries(clusterId).getFirst().getHealth()).isEqualTo("INACTIVE");
    }

    @Test
    void activeRefreshCannotPublishAfterConcurrentClusterInvalidation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Fixture fixture = new Fixture(executor);
            UUID clusterId = UUID.randomUUID();
            Cluster eligible = new Cluster();
            eligible.setId(clusterId);
            eligible.setStatus("SUCCESS");
            when(fixture.clusterRepository.findByStatusNot("DELETED"))
                    .thenReturn(List.of(eligible), List.of());
            CountDownLatch requestStarted = new CountDownLatch(1);
            CountDownLatch refreshFinished = new CountDownLatch(1);
            KafkaFutureImpl<Collection<ConsumerGroupListing>> future = new KafkaFutureImpl<>();
            ListConsumerGroupsResult result = listResult(future);
            when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenAnswer(invocation -> {
                requestStarted.countDown();
                return result;
            });

            fixture.service.reconcileClusters();
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            fixture.service.reconcileClusters();
            future.complete(List.of());
            // The executor task removes the in-flight guard only after refresh has returned.
            executor.submit(() -> refreshFinished.countDown()).get(5, TimeUnit.SECONDS);

            assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void freshLagSummaryCanBeReusedButExpiredOrFailedSnapshotCannot() throws Exception {
        Fixture fixture = new Fixture(Runnable::run);
        UUID clusterId = UUID.randomUUID();
        ListConsumerGroupsResult emptyGroups = listResult(KafkaFuture.completedFuture(List.of()));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(emptyGroups);
        fixture.service.refreshClusterNow(clusterId);
        assertThat(fixture.service.getFreshTotalLag(clusterId, 10_000).orElseThrow()).isZero();
        assertThat(fixture.service.getFreshTotalLag(clusterId, 10_000).orElseThrow()).isZero();
        verify(fixture.admin, times(1)).listConsumerGroups(any(ListConsumerGroupsOptions.class));

        ReflectionTestUtils.setField(fixture.service, "cacheTtlMillis", -1L);
        Object snapshot = ((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        assertThat(fixture.service.getFreshTotalLag(clusterId, 10_000)).isEmpty();
        ReflectionTestUtils.setField(fixture.service, "cacheTtlMillis", TimeUnit.SECONDS.toMillis(30));

        KafkaFutureImpl<Collection<ConsumerGroupListing>> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new IllegalStateException("refresh failed"));
        ListConsumerGroupsResult failedResult = listResult(failed);
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(failedResult);
        try { fixture.service.refreshClusterNow(clusterId); } catch (Exception expected) { }
        assertThat(fixture.service.getFreshTotalLag(clusterId, 10_000)).isEmpty();
    }

    @Test
    void concurrentScheduledCyclesDeduplicateRefreshForSameCluster() {
        List<Runnable> queued = new ArrayList<>();
        Executor queueingExecutor = queued::add;
        Fixture fixture = new Fixture(queueingExecutor);
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(fixture.clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        fixture.service.reconcileClusters();
        fixture.service.refreshCaches();

        assertThat(queued).hasSize(1);
    }

    @Test
    void routinePollsAvoidClusterQueriesAndRefreshOnlyClustersWhoseDueTimeHasArrived() {
        List<Runnable> queued = new ArrayList<>();
        Fixture fixture = new Fixture(queued::add);
        ReflectionTestUtils.setField(fixture.service, "reconciliationIntervalMillis", TimeUnit.MINUTES.toMillis(1));
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(fixture.clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));
        ListConsumerGroupsResult emptyGroups = listResult(KafkaFuture.completedFuture(List.of()));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(emptyGroups);

        fixture.service.reconcileClusters();
        Map<UUID, Long> due = (Map<UUID, Long>) ReflectionTestUtils.getField(fixture.service, "nextRefreshNanos");
        due.put(clusterId, 0L);
        fixture.service.refreshCaches();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();

        fixture.service.refreshCaches();
        fixture.service.refreshCaches();

        verify(fixture.clusterRepository, times(1)).findByStatusNot("DELETED");
        verify(fixture.kafkaAdminService, times(1)).getAdminClient(clusterId);
        assertThat(queued).isEmpty();

        Object snapshot = ((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        fixture.service.refreshCaches();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        verify(fixture.kafkaAdminService, times(2)).getAdminClient(clusterId);

        fixture.service.reconcileClusters();
        verify(fixture.clusterRepository, times(2)).findByStatusNot("DELETED");
        assertThat(queued).isEmpty();
    }

    @Test
    void staleFailedRefreshHonorsBackoffUntilRetryAndSuccessClearsFailureCount() {
        List<Runnable> queued = new ArrayList<>();
        Fixture fixture = new Fixture(queued::add);
        ReflectionTestUtils.setField(fixture.service, "reconciliationIntervalMillis", TimeUnit.SECONDS.toMillis(60));
        ReflectionTestUtils.setField(fixture.service, "refreshIntervalMillis", TimeUnit.SECONDS.toMillis(1));
        ReflectionTestUtils.setField(fixture.service, "retentionTtlMillis", Long.MAX_VALUE);
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(fixture.clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));
        ListConsumerGroupsResult good = listResult(KafkaFuture.completedFuture(List.of()));
        KafkaFutureImpl<Collection<ConsumerGroupListing>> failure = new KafkaFutureImpl<>();
        failure.completeExceptionally(new IllegalStateException("temporary failure"));
        ListConsumerGroupsResult failedGroups = listResult(failure);
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class)))
                .thenReturn(good, failedGroups, good);

        fixture.service.reconcileClusters();
        Map<UUID, Long> due = (Map<UUID, Long>) ReflectionTestUtils.getField(fixture.service, "nextRefreshNanos");
        due.put(clusterId, 0L);
        fixture.service.refreshCaches();
        queued.removeFirst().run();

        Object snapshot = ((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        due.put(clusterId, 0L);
        fixture.service.refreshCaches();
        queued.removeFirst().run();
        long retryDeadline = due.get(clusterId);

        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.FAILED);
        assertThat(retryDeadline).isGreaterThan(System.nanoTime());
        fixture.service.refreshCaches();
        assertThat(queued).isEmpty();

        due.put(clusterId, 0L);
        fixture.service.refreshCaches();
        queued.removeFirst().run();
        assertThat(fixture.service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.EMPTY);
        assertThat(((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "consecutiveFailures")).containsKey(clusterId)).isFalse();
        assertThat(fixture.service.retryDelayMillis(1)).isEqualTo(1_000L);
        assertThat(fixture.service.retryDelayMillis(2)).isEqualTo(2_000L);
        assertThat(fixture.service.retryDelayMillis(3)).isEqualTo(4_000L);
        assertThat(fixture.service.retryDelayMillis(7)).isEqualTo(60_000L);
        assertThat(fixture.service.retryDelayMillis(8)).isEqualTo(60_000L);
    }

    @Test
    void reconciliationDiscoversNewlyEligibleClustersAndRemovesIneligibleOnes() {
        Fixture fixture = new Fixture(ignored -> { });
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(fixture.clusterRepository.findByStatusNot("DELETED"))
                .thenReturn(List.of()).thenReturn(List.of(cluster)).thenReturn(List.of());

        fixture.service.reconcileClusters();
        assertThat(((Set<?>) ReflectionTestUtils.getField(fixture.service, "eligibleClusterIds")).contains(clusterId)).isFalse();

        fixture.service.reconcileClusters();
        assertThat(((Set<?>) ReflectionTestUtils.getField(fixture.service, "eligibleClusterIds")).contains(clusterId)).isTrue();
        assertThat(((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "nextRefreshNanos")).containsKey(clusterId)).isTrue();

        fixture.service.reconcileClusters();
        assertThat(((Set<?>) ReflectionTestUtils.getField(fixture.service, "eligibleClusterIds")).contains(clusterId)).isFalse();
        assertThat(((Map<?, ?>) ReflectionTestUtils.getField(fixture.service, "nextRefreshNanos")).containsKey(clusterId)).isFalse();
    }

    @Test
    void largeEligibleSetUsesBoundedAdmissionAndRetriesEveryRejectedCluster() {
        List<Runnable> queue = new ArrayList<>();
        Executor boundedExecutor = task -> {
            if (queue.size() >= 2) throw new RejectedExecutionException("test queue full");
            queue.add(task);
        };
        Fixture fixture = new Fixture(boundedExecutor);
        ReflectionTestUtils.setField(fixture.service, "reconciliationIntervalMillis", 1L);
        ReflectionTestUtils.setField(fixture.service, "refreshIntervalMillis", 1L);
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Cluster cluster = new Cluster();
            cluster.setId(UUID.randomUUID());
            cluster.setStatus("SUCCESS");
            clusters.add(cluster);
        }
        when(fixture.clusterRepository.findByStatusNot("DELETED")).thenReturn(clusters);
        ListConsumerGroupsResult emptyGroups = listResult(KafkaFuture.completedFuture(List.of()));
        when(fixture.admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(emptyGroups);
        fixture.service.reconcileClusters();
        assertThat(queue).hasSize(2);
        ReflectionTestUtils.setField(fixture.service, "reconciliationIntervalMillis", TimeUnit.MINUTES.toMillis(1));
        runQueued(queue);

        Map<UUID, Long> due = (Map<UUID, Long>) ReflectionTestUtils.getField(fixture.service, "nextRefreshNanos");
        int attempts = 0;
        while (verifyCompletedCount(fixture.service, clusters) < clusters.size() && attempts++ < 10) {
            for (Cluster cluster : clusters) {
                if (fixture.service.getCacheStatus(cluster.getId()).state() == CacheRefreshStatus.State.FAILED) {
                    due.put(cluster.getId(), 0L);
                }
            }
            fixture.service.refreshCaches();
            assertThat(queue.size()).isLessThanOrEqualTo(2);
            runQueued(queue);
        }

        assertThat(verifyCompletedCount(fixture.service, clusters)).isEqualTo(clusters.size());
        verify(fixture.clusterRepository, times(1)).findByStatusNot("DELETED");
        verify(fixture.kafkaAdminService, times(clusters.size())).getAdminClient(any());
    }

    private static void runQueued(List<Runnable> queue) {
        while (!queue.isEmpty()) queue.removeFirst().run();
    }

    private static long verifyCompletedCount(ConsumerLagCacheService service, List<Cluster> clusters) {
        return clusters.stream().filter(cluster -> service.getCacheStatus(cluster.getId()).state()
                == CacheRefreshStatus.State.EMPTY).count();
    }

    private static ConsumerGroupDescription description(String id) {
        return new ConsumerGroupDescription(id, false, List.of(), "range", ConsumerGroupState.STABLE,
                new Node(1, "localhost", 9092));
    }

    private static ListConsumerGroupsResult listResult(KafkaFuture<Collection<ConsumerGroupListing>> future) {
        ListConsumerGroupsResult result = mock(ListConsumerGroupsResult.class);
        when(result.all()).thenReturn(future);
        return result;
    }

    private static DescribeConsumerGroupsResult describeResult(KafkaFuture<Map<String, ConsumerGroupDescription>> future) {
        DescribeConsumerGroupsResult result = mock(DescribeConsumerGroupsResult.class);
        when(result.all()).thenReturn(future);
        return result;
    }

    private static ListConsumerGroupOffsetsResult offsetsResult(
            KafkaFuture<Map<String, Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata>>> future) {
        ListConsumerGroupOffsetsResult result = mock(ListConsumerGroupOffsetsResult.class);
        when(result.all()).thenReturn(future);
        return result;
    }

    private static ListOffsetsResult listOffsetsResult(
            KafkaFuture<Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo>> future) {
        ListOffsetsResult result = mock(ListOffsetsResult.class);
        when(result.all()).thenReturn(future);
        return result;
    }

    private static class Fixture {
        final ClusterRepository clusterRepository = mock(ClusterRepository.class);
        final KafkaAdminService kafkaAdminService = mock(KafkaAdminService.class);
        final AdminClient admin = mock(AdminClient.class);
        final ConsumerLagCacheService service;

        Fixture(Executor executor) {
            when(kafkaAdminService.getAdminClient(any())).thenReturn(admin);
            service = new ConsumerLagCacheService(clusterRepository, kafkaAdminService, executor);
            ReflectionTestUtils.setField(service, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));
            ReflectionTestUtils.setField(service, "cacheTtlMillis", TimeUnit.SECONDS.toMillis(30));
            ReflectionTestUtils.setField(service, "retentionTtlMillis", TimeUnit.MINUTES.toMillis(10));
        }
    }
}
