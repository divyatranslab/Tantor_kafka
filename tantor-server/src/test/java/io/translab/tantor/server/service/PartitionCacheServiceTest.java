package io.translab.tantor.server.service;

import io.translab.tantor.server.repository.ClusterRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PartitionCacheServiceTest {

    @Test
    void staleDataRemainsStaleUntilRetentionEvictsIt() throws Exception {
        ClusterRepository clusters = mock(ClusterRepository.class);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        AdminClient admin = mock(AdminClient.class);
        ListTopicsResult topics = mock(ListTopicsResult.class);
        UUID clusterId = UUID.randomUUID();
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);
        when(admin.listTopics(any())).thenReturn(topics);
        when(topics.names()).thenReturn(KafkaFuture.completedFuture(Set.of()));
        PartitionCacheService service = new PartitionCacheService(clusters, kafka, Runnable::run);
        ReflectionTestUtils.setField(service, "cacheTtlMillis", -1L);
        ReflectionTestUtils.setField(service, "retentionTtlMillis", TimeUnit.MINUTES.toMillis(10));
        ReflectionTestUtils.setField(service, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));

        service.refreshClusterNow(clusterId);
        ReflectionTestUtils.setField(service, "retentionTtlMillis", Long.MAX_VALUE);
        Object snapshot = ((java.util.Map<?, ?>) ReflectionTestUtils.getField(service, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.STALE);
        ReflectionTestUtils.setField(service, "retentionTtlMillis", -1L);
        assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
        assertThat(service.getPaginatedPartitions(clusterId, 0, 10, null, null).getContent()).isEmpty();
    }

    @Test
    void queuedRefreshCannotRecreateCacheAfterClusterBecomesIneligible() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ClusterRepository clusters = mock(ClusterRepository.class);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        UUID clusterId = UUID.randomUUID();
        io.translab.tantor.server.domain.Cluster eligible = new io.translab.tantor.server.domain.Cluster();
        eligible.setId(clusterId);
        eligible.setStatus("SUCCESS");
        when(clusters.findByStatusNot("DELETED")).thenReturn(java.util.List.of(eligible), java.util.List.of());
        PartitionCacheService service = new PartitionCacheService(clusters, kafka, queued::add);

        service.reconcileClusters();
        service.reconcileClusters();
        queued.getFirst().run();

        assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
        verify(kafka, never()).getAdminClient(clusterId);
        assertThat(ReflectionTestUtils.getField(service, "metadataSnapshots")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
    }

    @Test
    void metadataRefreshCannotRepublishAfterConcurrentClusterInvalidation() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ClusterRepository clusters = mock(ClusterRepository.class);
            KafkaAdminService kafka = mock(KafkaAdminService.class);
            AdminClient admin = mock(AdminClient.class);
            UUID clusterId = UUID.randomUUID();
            io.translab.tantor.server.domain.Cluster eligible = new io.translab.tantor.server.domain.Cluster();
            eligible.setId(clusterId);
            eligible.setStatus("SUCCESS");
            when(clusters.findByStatusNot("DELETED")).thenReturn(java.util.List.of(eligible), java.util.List.of());
            when(kafka.getAdminClient(clusterId)).thenReturn(admin);
            CountDownLatch requestStarted = new CountDownLatch(1);
            KafkaFutureImpl<Set<String>> future = new KafkaFutureImpl<>();
            ListTopicsResult topics = mock(ListTopicsResult.class);
            when(topics.names()).thenReturn(future);
            when(admin.listTopics(any())).thenAnswer(invocation -> {
                requestStarted.countDown();
                return topics;
            });
            PartitionCacheService service = new PartitionCacheService(clusters, kafka, executor);
            ReflectionTestUtils.setField(service, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));

            service.reconcileClusters();
            assertThat(requestStarted.await(5, TimeUnit.SECONDS)).isTrue();
            service.reconcileClusters();
            future.complete(Set.of());
            executor.submit(() -> {}).get(5, TimeUnit.SECONDS);

            assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.UNINITIALIZED);
            assertThat(ReflectionTestUtils.getField(service, "metadataSnapshots"))
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).isEmpty();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reusesEmptyTopicMetadataUntilInvalidatedAndStillPublishesValidEmptyCache() throws Exception {
        ClusterRepository clusters = mock(ClusterRepository.class);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        AdminClient admin = mock(AdminClient.class);
        ListTopicsResult topics = mock(ListTopicsResult.class);
        UUID clusterId = UUID.randomUUID();
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);
        when(admin.listTopics(any())).thenReturn(topics);
        when(topics.names()).thenReturn(KafkaFuture.completedFuture(Set.of()));
        PartitionCacheService service = new PartitionCacheService(clusters, kafka, Runnable::run);
        ReflectionTestUtils.setField(service, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(service, "cacheTtlMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(service, "metadataTtlMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(service, "retentionTtlMillis", TimeUnit.MINUTES.toMillis(10));

        service.refreshClusterNow(clusterId);
        service.refreshClusterNow(clusterId);
        assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.EMPTY);
        assertThat(service.getPaginatedPartitions(clusterId, 0, 10, null, "topicName").getContent()).isEmpty();
        verify(admin, times(1)).listTopics(any());
        verify(admin, never()).describeTopics(anyCollection(), any());

        service.invalidateMetadata(clusterId);
        service.refreshClusterNow(clusterId);
        verify(admin, times(2)).listTopics(any());
    }

    @Test
    void routinePollDoesNotQueryClustersAndMetadataInvalidationSchedulesOneClusterPromptly() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ClusterRepository clusters = mock(ClusterRepository.class);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        AdminClient admin = mock(AdminClient.class);
        ListTopicsResult topics = mock(ListTopicsResult.class);
        UUID clusterId = UUID.randomUUID();
        io.translab.tantor.server.domain.Cluster cluster = new io.translab.tantor.server.domain.Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(clusters.findByStatusNot("DELETED")).thenReturn(java.util.List.of(cluster));
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);
        when(admin.listTopics(any())).thenReturn(topics);
        when(topics.names()).thenReturn(KafkaFuture.completedFuture(Set.of()));
        PartitionCacheService service = new PartitionCacheService(clusters, kafka, queued::add);
        ReflectionTestUtils.setField(service, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(service, "reconciliationIntervalMillis", TimeUnit.MINUTES.toMillis(1));

        service.reconcileClusters();
        java.util.Map<UUID, Long> due = (java.util.Map<UUID, Long>) ReflectionTestUtils.getField(service, "nextRefreshNanos");
        due.put(clusterId, 0L);
        service.refreshCaches();
        queued.removeFirst().run();
        service.refreshCaches();
        service.refreshCaches();
        verify(clusters, times(1)).findByStatusNot("DELETED");
        verify(admin, times(1)).listTopics(any());

        service.invalidateMetadata(clusterId);
        service.refreshCaches();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        verify(admin, times(2)).listTopics(any());
    }

    @Test
    void staleFailedRefreshHonorsBackoffAndMetadataInvalidationRetriesPromptly() {
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        ClusterRepository clusters = mock(ClusterRepository.class);
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        AdminClient admin = mock(AdminClient.class);
        UUID clusterId = UUID.randomUUID();
        io.translab.tantor.server.domain.Cluster cluster = new io.translab.tantor.server.domain.Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(clusters.findByStatusNot("DELETED")).thenReturn(java.util.List.of(cluster));
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);
        ListTopicsResult good = mock(ListTopicsResult.class);
        when(good.names()).thenReturn(KafkaFuture.completedFuture(Set.of()));
        KafkaFutureImpl<Set<String>> failedNames = new KafkaFutureImpl<>();
        failedNames.completeExceptionally(new IllegalStateException("temporary failure"));
        ListTopicsResult failed = mock(ListTopicsResult.class);
        when(failed.names()).thenReturn(failedNames);
        when(admin.listTopics(any())).thenReturn(good, failed, good);
        PartitionCacheService service = new PartitionCacheService(clusters, kafka, queued::add);
        ReflectionTestUtils.setField(service, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(service, "cacheTtlMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(service, "retentionTtlMillis", Long.MAX_VALUE);
        ReflectionTestUtils.setField(service, "reconciliationIntervalMillis", TimeUnit.SECONDS.toMillis(60));
        ReflectionTestUtils.setField(service, "refreshIntervalMillis", TimeUnit.SECONDS.toMillis(1));

        service.reconcileClusters();
        java.util.Map<UUID, Long> due = (java.util.Map<UUID, Long>) ReflectionTestUtils.getField(service, "nextRefreshNanos");
        due.put(clusterId, 0L);
        service.refreshCaches();
        queued.removeFirst().run();

        Object snapshot = ((java.util.Map<?, ?>) ReflectionTestUtils.getField(service, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        service.invalidateMetadata(clusterId);
        due.put(clusterId, 0L);
        service.refreshCaches();
        queued.removeFirst().run();
        long retryDeadline = due.get(clusterId);

        assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.FAILED);
        assertThat(retryDeadline).isGreaterThan(System.nanoTime());
        service.refreshCaches();
        assertThat(queued).isEmpty();

        service.invalidateMetadata(clusterId);
        service.refreshCaches();
        assertThat(queued).hasSize(1);
        queued.removeFirst().run();
        assertThat(service.getCacheStatus(clusterId).state()).isEqualTo(CacheRefreshStatus.State.EMPTY);
        assertThat(((java.util.Map<?, ?>) ReflectionTestUtils.getField(service, "consecutiveFailures")).containsKey(clusterId)).isFalse();
        assertThat(service.retryDelayMillis(1)).isEqualTo(1_000L);
        assertThat(service.retryDelayMillis(2)).isEqualTo(2_000L);
        assertThat(service.retryDelayMillis(3)).isEqualTo(4_000L);
        assertThat(service.retryDelayMillis(7)).isEqualTo(60_000L);
        assertThat(service.retryDelayMillis(8)).isEqualTo(60_000L);
    }
}
