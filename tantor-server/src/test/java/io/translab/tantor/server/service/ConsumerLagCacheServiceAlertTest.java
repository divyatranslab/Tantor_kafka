package io.translab.tantor.server.service;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.dto.ConsumerGroupSummaryDto;
import io.translab.tantor.server.repository.ClusterRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsumerLagCacheServiceAlertTest {

    private ClusterRepository clusterRepository;
    private KafkaAdminService kafkaAdminService;
    private TransactionTemplate transactionTemplate;
    private ConsumerLagCacheService service;

    @BeforeEach
    void setUp() {
        clusterRepository = mock(ClusterRepository.class);
        kafkaAdminService = mock(KafkaAdminService.class);
        transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            org.springframework.transaction.support.TransactionCallback<?> cb = invocation.getArgument(0);
            return cb.doInTransaction(mock(org.springframework.transaction.TransactionStatus.class));
        });
        service = new ConsumerLagCacheService(clusterRepository, kafkaAdminService, transactionTemplate);
    }

    @Test
    void alertSummariesFiltersAndLimitsCorrectlyOnClusterCacheUpdate() {
        UUID clusterId = UUID.randomUUID();

        ConsumerGroupSummaryDto healthyBelowThreshold = new ConsumerGroupSummaryDto("g-healthy-low", "Stable", 1, 500,
                "HEALTHY", System.currentTimeMillis());
        ConsumerGroupSummaryDto healthyAboveThreshold = new ConsumerGroupSummaryDto("g-healthy-high", "Stable", 1, 1500,
                "HEALTHY", System.currentTimeMillis());
        ConsumerGroupSummaryDto warningLowLag = new ConsumerGroupSummaryDto("g-warning-low", "Stable", 1, 100,
                "WARNING", System.currentTimeMillis());
        ConsumerGroupSummaryDto criticalLag = new ConsumerGroupSummaryDto("g-critical", "Stable", 1, 12000, "HEALTHY",
                System.currentTimeMillis());

        long initialVersion = service.getCacheVersion();

        service.setCacheForCluster(clusterId, List.of(
                healthyBelowThreshold, healthyAboveThreshold, warningLowLag, criticalLag));

        assertThat(service.getCacheVersion()).isGreaterThan(initialVersion);

        List<ConsumerGroupSummaryDto> alertSummaries = service.getAlertSummaries(clusterId);
        assertThat(alertSummaries).hasSize(3);
        assertThat(alertSummaries).extracting(ConsumerGroupSummaryDto::getGroupId)
                .containsExactlyInAnyOrder("g-healthy-high", "g-warning-low", "g-critical");
    }

    @Test
    void alertSummariesLimitsToTenGroups() {
        UUID clusterId = UUID.randomUUID();

        List<ConsumerGroupSummaryDto> groups = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            groups.add(new ConsumerGroupSummaryDto("group-" + i, "Stable", 1, 2000 + i, "HEALTHY",
                    System.currentTimeMillis()));
        }

        service.setCacheForCluster(clusterId, groups);

        List<ConsumerGroupSummaryDto> alertSummaries = service.getAlertSummaries(clusterId);
        assertThat(alertSummaries).hasSize(10);
    }

    @Test
    void emptySummariesResultsInEmptyAlertSummaries() {
        UUID clusterId = UUID.randomUUID();

        service.setCacheForCluster(clusterId, List.of());

        assertThat(service.getAlertSummaries(clusterId)).isEmpty();
        assertThat(service.getSummaries(clusterId)).isEmpty();
    }

    @Test
    void refreshCachesCleansUpDeletedClusterFromAlertCache() {
        UUID activeClusterId = UUID.randomUUID();
        UUID deletedClusterId = UUID.randomUUID();

        service.setCacheForCluster(activeClusterId, List.of(
                new ConsumerGroupSummaryDto("active-g", "Stable", 1, 5000, "HEALTHY", System.currentTimeMillis())));
        service.setCacheForCluster(deletedClusterId, List.of(
                new ConsumerGroupSummaryDto("deleted-g", "Stable", 1, 5000, "HEALTHY", System.currentTimeMillis())));

        assertThat(service.getAlertSummaries(deletedClusterId)).isNotEmpty();

        Cluster activeCluster = new Cluster();
        activeCluster.setId(activeClusterId);
        activeCluster.setStatus("FAILED"); // Not SUCCESS/EXTERNAL, so refreshConsumerGroups won't run

        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(activeCluster));

        service.refreshCaches();

        assertThat(service.getAlertSummaries(deletedClusterId)).isEmpty();
        assertThat(service.getSummaries(deletedClusterId)).isEmpty();
    }

    @Test
    void failedRefreshRetainsPreviousValidSummariesAndDoesNotFalselyAdvanceGeneration() {
        UUID clusterId = UUID.randomUUID();
        service.setCacheForCluster(clusterId, List.of(
                new ConsumerGroupSummaryDto("stable-group", "Stable", 1, 5000, "HEALTHY", 1000L)));

        assertThat(service.getAlertSummaries(clusterId)).hasSize(1);
        long initialVersion = service.getCacheVersion();

        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");

        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));
        when(kafkaAdminService.getAdminClient(clusterId)).thenThrow(new RuntimeException("Kafka unreachable"));

        service.refreshCaches();

        // 1. Valid data is retained
        assertThat(service.getAlertSummaries(clusterId)).hasSize(1);
        ConsumerGroupSummaryDto summary = service.getAlertSummaries(clusterId).get(0);
        assertThat(summary.getGroupId()).isEqualTo("stable-group");
        assertThat(summary.getLastUpdated()).isEqualTo(1000L);

        // 2. Generation does NOT advance on failed refresh, so stale summaries are not
        // marked fresh
        assertThat(service.getCacheVersion()).isEqualTo(initialVersion);
    }

    @Test
    void partialRefreshFailurePreservesFailedClusterWhileUpdatingSuccessfulCluster() throws Exception {
        UUID successfulClusterId = UUID.randomUUID();
        UUID failedClusterId = UUID.randomUUID();

        service.setCacheForCluster(successfulClusterId, List.of(
                new ConsumerGroupSummaryDto("old-success-group", "Stable", 1, 4000, "HEALTHY", 1000L)));
        service.setCacheForCluster(failedClusterId, List.of(
                new ConsumerGroupSummaryDto("stable-failed-group", "Stable", 1, 6000, "HEALTHY", 2000L)));

        long initialVersion = service.getCacheVersion();
        long previousTimestamp = service.getClusterLastRefreshed(failedClusterId);

        Cluster successCluster = new Cluster();
        successCluster.setId(successfulClusterId);
        successCluster.setStatus("SUCCESS");
        Cluster failCluster = new Cluster();
        failCluster.setId(failedClusterId);
        failCluster.setStatus("SUCCESS");

        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(successCluster, failCluster));

        // Successful cluster returns empty group listings (resolved lag)
        AdminClient successClient = mock(AdminClient.class);
        ListConsumerGroupsResult listResult = mock(ListConsumerGroupsResult.class);
        KafkaFuture<Collection<org.apache.kafka.clients.admin.ConsumerGroupListing>> future = KafkaFuture
                .completedFuture(List.of());
        when(successClient.listConsumerGroups()).thenReturn(listResult);
        when(listResult.all()).thenReturn(future);
        when(kafkaAdminService.getAdminClient(successfulClusterId)).thenReturn(successClient);

        // Failed cluster throws
        when(kafkaAdminService.getAdminClient(failedClusterId)).thenThrow(new RuntimeException("Broker disconnected"));

        service.refreshCaches();

        // Generation increments because successfulCluster refreshed
        assertThat(service.getCacheVersion()).isGreaterThan(initialVersion);

        // Successful cluster updated (lag cleared)
        assertThat(service.getAlertSummaries(successfulClusterId)).isEmpty();

        // Failed cluster preserves last valid summaries and timestamp
        assertThat(service.getAlertSummaries(failedClusterId)).hasSize(1);
        assertThat(service.getAlertSummaries(failedClusterId).get(0).getGroupId()).isEqualTo("stable-failed-group");
        assertThat(service.getAlertSummaries(failedClusterId).get(0).getLastUpdated()).isEqualTo(2000L);

        // Per-cluster freshness metadata tracks failed cluster preserving its previous
        // timestamp
        assertThat(service.getClusterLastRefreshed(failedClusterId)).isEqualTo(previousTimestamp);
        assertThat(service.getAlertCacheGeneration().clusterLastRefreshed().get(failedClusterId))
                .isEqualTo(previousTimestamp);
    }

    @Test
    void successfulRefreshUpdatesSummariesAndAdvancesGeneration() throws Exception {
        UUID clusterId = UUID.randomUUID();
        service.setCacheForCluster(clusterId, List.of(
                new ConsumerGroupSummaryDto("initial-group", "Stable", 1, 2000, "HEALTHY", 1000L)));
        long initialVersion = service.getCacheVersion();

        Cluster cluster = new Cluster();
        cluster.setId(clusterId);
        cluster.setStatus("SUCCESS");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        AdminClient client = mock(AdminClient.class);
        ListConsumerGroupsResult listResult = mock(ListConsumerGroupsResult.class);
        KafkaFuture<Collection<org.apache.kafka.clients.admin.ConsumerGroupListing>> future = KafkaFuture
                .completedFuture(List.of());
        when(client.listConsumerGroups()).thenReturn(listResult);
        when(listResult.all()).thenReturn(future);
        when(kafkaAdminService.getAdminClient(clusterId)).thenReturn(client);

        service.refreshCaches();

        // Successful refresh clears old lag alert (empty list) and advances generation
        assertThat(service.getCacheVersion()).isGreaterThan(initialVersion);
        assertThat(service.getAlertSummaries(clusterId)).isEmpty();
    }

    @Test
    void skippedClustersFollowDocumentedCachePolicy() {
        UUID skippedClusterId = UUID.randomUUID();
        service.setCacheForCluster(skippedClusterId, List.of(
                new ConsumerGroupSummaryDto("preserved-group", "Stable", 1, 3000, "HEALTHY", 1000L)));

        Cluster skippedCluster = new Cluster();
        skippedCluster.setId(skippedClusterId);
        skippedCluster.setStatus("CREATING"); // Not SUCCESS/EXTERNAL
        skippedCluster.setMode("INTERNAL");

        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(skippedCluster));

        service.refreshCaches();

        // Kafka admin client was never invoked for skipped cluster
        verify(kafkaAdminService, never()).getAdminClient(skippedClusterId);

        // Cached summaries are preserved for non-deleted, skipped cluster
        assertThat(service.getAlertSummaries(skippedClusterId)).hasSize(1);
        assertThat(service.getAlertSummaries(skippedClusterId).get(0).getGroupId()).isEqualTo("preserved-group");
    }

    @Test
    void alertCacheGenerationReturnsDeeplyImmutableSnapshot() {
        UUID clusterId = UUID.randomUUID();
        service.setCacheForCluster(clusterId, List.of(
                new ConsumerGroupSummaryDto("g1", "Stable", 1, 2000, "HEALTHY", System.currentTimeMillis())));

        ConsumerLagCacheService.AlertCacheGeneration gen = service.getAlertCacheGeneration();
        assertThat(gen).isNotNull();
        assertThat(gen.generation()).isGreaterThan(0);
        assertThat(gen.alertSummaries().get(clusterId)).hasSize(1);

        // Map cannot be modified
        assertThatThrownBy(() -> gen.alertSummaries().put(UUID.randomUUID(), List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> gen.alertSummaries().remove(clusterId))
                .isInstanceOf(UnsupportedOperationException.class);

        // Summary list inside the map cannot be modified
        List<ConsumerGroupSummaryDto> clusterSummaries = gen.alertSummaries().get(clusterId);
        assertThatThrownBy(() -> clusterSummaries.add(new ConsumerGroupSummaryDto()))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> clusterSummaries.remove(0))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(clusterSummaries::clear)
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void concurrentReadsAndRefreshesNeverExposePartialGenerations() throws Exception {
        UUID clusterA = UUID.randomUUID();
        UUID clusterB = UUID.randomUUID();

        service.setCacheForCluster(clusterA, List.of(
                new ConsumerGroupSummaryDto("group-a", "Stable", 1, 2000, "HEALTHY", System.currentTimeMillis())));
        service.setCacheForCluster(clusterB, List.of(
                new ConsumerGroupSummaryDto("group-b", "Stable", 1, 3000, "HEALTHY", System.currentTimeMillis())));

        int readers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(readers + 1);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(readers + 1);
        List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Writer thread performing concurrent updates
        executor.submit(() -> {
            try {
                startLatch.await();
                for (int i = 0; i < 50; i++) {
                    service.setCacheForCluster(clusterA, List.of(
                            new ConsumerGroupSummaryDto("group-a-" + i, "Stable", 1, 2000 + i, "HEALTHY",
                                    System.currentTimeMillis())));
                }
            } catch (Throwable t) {
                errors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Reader threads inspecting generation snapshots
        for (int r = 0; r < readers; r++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long lastSeenGen = -1;
                    for (int i = 0; i < 100; i++) {
                        ConsumerLagCacheService.AlertCacheGeneration gen = service.getAlertCacheGeneration();
                        assertThat(gen).isNotNull();
                        assertThat(gen.generation()).isGreaterThanOrEqualTo(lastSeenGen);
                        lastSeenGen = gen.generation();

                        Map<UUID, List<ConsumerGroupSummaryDto>> map = gen.alertSummaries();
                        assertThat(map).isNotNull();
                        // Never partial: both cluster entries exist and contain valid lists
                        assertThat(map.containsKey(clusterA)).isTrue();
                        assertThat(map.get(clusterA)).isNotEmpty();
                        assertThat(map.containsKey(clusterB)).isTrue();
                        assertThat(map.get(clusterB)).isNotEmpty();
                    }
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(errors).isEmpty();
    }
}
