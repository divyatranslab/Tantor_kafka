package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Alert;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.dto.ConsumerGroupSummaryDto;
import io.translab.tantor.server.repository.AlertRepository;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostParcelRepository;
import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.TaskRepository;
import io.translab.tantor.server.service.ConsumerLagCacheService;
import io.translab.tantor.server.service.HostStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AlertControllerConsumerLagCacheTest {

    private AlertRepository alertRepository;
    private ClusterRepository clusterRepository;
    private HostRepository hostRepository;
    private TaskRepository taskRepository;
    private HostParcelRepository hostParcelRepository;
    private HostStatusService hostStatusService;
    private ConsumerLagCacheService consumerLagCacheService;

    private final List<Alert> persistedAlerts = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        alertRepository = mock(AlertRepository.class);
        clusterRepository = mock(ClusterRepository.class);
        hostRepository = mock(HostRepository.class);
        taskRepository = mock(TaskRepository.class);
        hostParcelRepository = mock(HostParcelRepository.class);
        hostStatusService = mock(HostStatusService.class);
        consumerLagCacheService = mock(ConsumerLagCacheService.class);

        persistedAlerts.clear();

        when(hostRepository.findAll()).thenReturn(List.of());
        when(taskRepository.findAll()).thenReturn(List.of());
        when(hostParcelRepository.findAll()).thenReturn(List.of());

        when(alertRepository.findByAlertKey(any())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return persistedAlerts.stream().filter(a -> key.equals(a.getAlertKey())).findFirst();
        });

        when(alertRepository.save(any(Alert.class))).thenAnswer(invocation -> {
            Alert alert = invocation.getArgument(0);
            if (alert.getId() == null) {
                alert.setId(UUID.randomUUID());
            }
            if (alert.getUpdatedAt() == null) {
                alert.setUpdatedAt(Instant.now());
            }
            if (!persistedAlerts.contains(alert)) {
                persistedAlerts.add(alert);
            }
            return alert;
        });

        when(alertRepository.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenAnswer(ignored ->
                persistedAlerts.stream().filter(a -> "ACTIVE".equalsIgnoreCase(a.getStatus())).toList());
        when(alertRepository.findTop100ByOrderByUpdatedAtDesc()).thenAnswer(ignored -> new ArrayList<>(persistedAlerts));
    }

    private Cluster createCluster(UUID id, String name) {
        Cluster cluster = new Cluster();
        cluster.setId(id);
        cluster.setName(name);
        cluster.setStatus("RUNNING");
        return cluster;
    }

    @Test
    void repeatedAlertRequestsReusePreparedSnapshotInsteadOfRebuilding() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "test-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto group1 = new ConsumerGroupSummaryDto("orders-group", "Stable", 3, 5000, "HEALTHY", System.currentTimeMillis());
        when(consumerLagCacheService.getCacheVersion()).thenReturn(1L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(List.of(group1));

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        // First call populates the snapshot
        var response1 = controller.getActiveAlerts();
        assertThat(response1.getBody()).anyMatch(alert -> ("consumer-lag-" + clusterId + "-orders-group").equals(alert.get("id")));

        // Second call should reuse the prepared snapshot without calling getAlertSummaries again
        var response2 = controller.getActiveAlerts();
        assertThat(response2.getBody()).anyMatch(alert -> ("consumer-lag-" + clusterId + "-orders-group").equals(alert.get("id")));

        // getAlertSummaries should have been invoked exactly ONCE across the two requests, and getSummaries never
        verify(consumerLagCacheService, times(1)).getAlertSummaries(clusterId);
        verify(consumerLagCacheService, never()).getSummaries(clusterId);
    }

    @Test
    void existingAlertThresholdsSeverityMessagesAndStructurePreserved() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "metrics-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        // Group 1: lag 1000, HEALTHY -> WARNING
        ConsumerGroupSummaryDto warningLagGroup = new ConsumerGroupSummaryDto("warn-lag", "Stable", 2, 1000, "HEALTHY", System.currentTimeMillis());
        // Group 2: lag 500, WARNING health -> WARNING
        ConsumerGroupSummaryDto warningHealthGroup = new ConsumerGroupSummaryDto("warn-health", "Stable", 1, 500, "WARNING", System.currentTimeMillis());
        // Group 3: lag 15000 -> CRITICAL
        ConsumerGroupSummaryDto criticalLagGroup = new ConsumerGroupSummaryDto("crit-lag", "Stable", 4, 15000, "HEALTHY", System.currentTimeMillis());

        when(consumerLagCacheService.getCacheVersion()).thenReturn(10L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(List.of(warningLagGroup, warningHealthGroup, criticalLagGroup));

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response = controller.getActiveAlerts();
        List<Map<String, Object>> alerts = response.getBody();

        assertThat(alerts).isNotNull();

        // Verify warning lag alert
        Map<String, Object> warnLagAlert = alerts.stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-warn-lag").equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(warnLagAlert.get("severity")).isEqualTo("WARNING");
        assertThat(warnLagAlert.get("title")).isEqualTo("Consumer group needs attention");
        assertThat(warnLagAlert.get("description")).isEqualTo("warn-lag has total lag 1000 and health HEALTHY.");
        assertThat(warnLagAlert.get("clusterId")).isEqualTo(clusterId);
        assertThat(warnLagAlert.get("clusterName")).isEqualTo("metrics-cluster");

        // Verify warning health alert
        Map<String, Object> warnHealthAlert = alerts.stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-warn-health").equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(warnHealthAlert.get("severity")).isEqualTo("WARNING");
        assertThat(warnHealthAlert.get("description")).isEqualTo("warn-health has total lag 500 and health WARNING.");

        // Verify critical lag alert
        Map<String, Object> critAlert = alerts.stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-crit-lag").equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(critAlert.get("severity")).isEqualTo("CRITICAL");
        assertThat(critAlert.get("description")).isEqualTo("crit-lag has total lag 15000 and health HEALTHY.");
    }

    @Test
    void multipleClustersAndConsumerGroupsProduceExpectedAlerts() {
        UUID cluster1Id = UUID.randomUUID();
        UUID cluster2Id = UUID.randomUUID();
        Cluster cluster1 = createCluster(cluster1Id, "cluster-alpha");
        Cluster cluster2 = createCluster(cluster2Id, "cluster-beta");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster1, cluster2));

        ConsumerGroupSummaryDto groupA = new ConsumerGroupSummaryDto("group-a", "Stable", 1, 2000, "HEALTHY", System.currentTimeMillis());
        ConsumerGroupSummaryDto groupB = new ConsumerGroupSummaryDto("group-b", "Stable", 2, 12000, "HEALTHY", System.currentTimeMillis());

        when(consumerLagCacheService.getCacheVersion()).thenReturn(1L);
        when(consumerLagCacheService.getAlertSummaries(cluster1Id)).thenReturn(List.of(groupA));
        when(consumerLagCacheService.getAlertSummaries(cluster2Id)).thenReturn(List.of(groupB));

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response = controller.getActiveAlerts();
        List<Map<String, Object>> alerts = response.getBody();
        assertThat(alerts).isNotNull();

        assertThat(alerts).anyMatch(a -> ("consumer-lag-" + cluster1Id + "-group-a").equals(a.get("id"))
                && "cluster-alpha".equals(a.get("clusterName")));
        assertThat(alerts).anyMatch(a -> ("consumer-lag-" + cluster2Id + "-group-b").equals(a.get("id"))
                && "cluster-beta".equals(a.get("clusterName")));
    }

    @Test
    void cacheRefreshUpdatesAlertSnapshotWhenConsumerGroupSummariesChange() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "dynamic-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto oldGroup = new ConsumerGroupSummaryDto("old-group", "Stable", 1, 3000, "HEALTHY", System.currentTimeMillis());
        ConsumerGroupSummaryDto newGroup = new ConsumerGroupSummaryDto("new-group", "Stable", 2, 7000, "HEALTHY", System.currentTimeMillis());

        when(consumerLagCacheService.getCacheVersion()).thenReturn(1L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(List.of(oldGroup));

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response1 = controller.getActiveAlerts();
        assertThat(response1.getBody()).anyMatch(a -> ("consumer-lag-" + clusterId + "-old-group").equals(a.get("id")));

        // Cache refreshes: version increments and alert summaries change
        when(consumerLagCacheService.getCacheVersion()).thenReturn(2L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(List.of(newGroup));

        var response2 = controller.getActiveAlerts();
        assertThat(response2.getBody()).anyMatch(a -> ("consumer-lag-" + clusterId + "-new-group").equals(a.get("id")));

        // getAlertSummaries was invoked twice: once for version 1, once for version 2
        verify(consumerLagCacheService, times(2)).getAlertSummaries(clusterId);
    }

    @Test
    void emptySummariesAndClustersWithNoConsumerGroupsHandledCorrectly() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "empty-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        when(consumerLagCacheService.getCacheVersion()).thenReturn(5L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(List.of());

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response = controller.getActiveAlerts();
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void fallbackToGetSummariesWhenGetAlertSummariesReturnsNull() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "fallback-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto group = new ConsumerGroupSummaryDto("fallback-group", "Stable", 1, 4000, "HEALTHY", System.currentTimeMillis());
        when(consumerLagCacheService.getCacheVersion()).thenReturn(1L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(null);
        when(consumerLagCacheService.getSummaries(clusterId)).thenReturn(List.of(group));

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response = controller.getActiveAlerts();
        assertThat(response.getBody()).anyMatch(a -> ("consumer-lag-" + clusterId + "-fallback-group").equals(a.get("id")));
    }

    @Test
    void concurrentReadsDuringRefreshDoNotExposePartiallyUpdatedResults() throws Exception {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "concurrency-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto groupA = new ConsumerGroupSummaryDto("group-a", "Stable", 1, 2000, "HEALTHY", System.currentTimeMillis());
        ConsumerGroupSummaryDto groupB = new ConsumerGroupSummaryDto("group-b", "Stable", 2, 4000, "HEALTHY", System.currentTimeMillis());

        when(consumerLagCacheService.getCacheVersion()).thenReturn(1L);
        when(consumerLagCacheService.getAlertSummaries(clusterId)).thenReturn(List.of(groupA, groupB));

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threads);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 50; j++) {
                        var response = controller.getActiveAlerts();
                        assertThat(response.getBody()).isNotNull();
                        List<Map<String, Object>> alerts = response.getBody();
                        boolean hasA = alerts.stream().anyMatch(a -> ("consumer-lag-" + clusterId + "-group-a").equals(a.get("id")));
                        boolean hasB = alerts.stream().anyMatch(a -> ("consumer-lag-" + clusterId + "-group-b").equals(a.get("id")));
                        // Both must be present atomically, never partial
                        assertThat(hasA).isEqualTo(hasB);
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

    @Test
    void alertControllerUsesAlertCacheGenerationWhenAvailable() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "gen-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto group = new ConsumerGroupSummaryDto("gen-group", "Stable", 1, 4000, "HEALTHY", System.currentTimeMillis());
        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L, Map.of(clusterId, List.of(group))
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response1 = controller.getActiveAlerts();
        assertThat(response1.getBody()).anyMatch(a -> ("consumer-lag-" + clusterId + "-gen-group").equals(a.get("id")));

        // Second call with same generation reuses snapshot
        var response2 = controller.getActiveAlerts();
        assertThat(response2.getBody()).anyMatch(a -> ("consumer-lag-" + clusterId + "-gen-group").equals(a.get("id")));

        // Now generation updates atomically
        ConsumerGroupSummaryDto updatedGroup = new ConsumerGroupSummaryDto("updated-group", "Stable", 2, 8000, "HEALTHY", System.currentTimeMillis());
        ConsumerLagCacheService.AlertCacheGeneration gen2 = new ConsumerLagCacheService.AlertCacheGeneration(
                2L, Map.of(clusterId, List.of(updatedGroup))
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen2);

        var response3 = controller.getActiveAlerts();
        assertThat(response3.getBody()).anyMatch(a -> ("consumer-lag-" + clusterId + "-updated-group").equals(a.get("id")));
    }

    @Test
    void failedRefreshRetainingGenerationReusesExistingSnapshotWithoutRebuilding() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "stale-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto group = new ConsumerGroupSummaryDto("stale-group", "Stable", 1, 5000, "HEALTHY", 1000L);
        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L, Map.of(clusterId, List.of(group))
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response1 = controller.getActiveAlerts();
        List<Map<String, Object>> alerts1 = response1.getBody();
        assertThat(alerts1).isNotEmpty();

        // Refresh runs and fails: generation 1L is retained
        // Controller must reuse the prepared alert snapshot without rebuilding
        var response2 = controller.getActiveAlerts();
        List<Map<String, Object>> alerts2 = response2.getBody();

        // Identical alert instances and timestamps preserved
        Map<String, Object> alert1 = alerts1.stream().filter(a -> ("consumer-lag-" + clusterId + "-stale-group").equals(a.get("id"))).findFirst().orElseThrow();
        Map<String, Object> alert2 = alerts2.stream().filter(a -> ("consumer-lag-" + clusterId + "-stale-group").equals(a.get("id"))).findFirst().orElseThrow();
        assertThat(alert2.get("createdAt")).isEqualTo(alert1.get("createdAt"));
    }

    @Test
    void partialClusterFailurePreservesFailedClusterTimestampWhileUpdatingSuccessfulCluster() {
        UUID clusterSuccessId = UUID.randomUUID();
        UUID clusterFailedId = UUID.randomUUID();
        Cluster clusterSuccess = createCluster(clusterSuccessId, "success-cluster");
        Cluster clusterFailed = createCluster(clusterFailedId, "failed-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(clusterSuccess, clusterFailed));

        ConsumerGroupSummaryDto successGroupT1 = new ConsumerGroupSummaryDto("success-group", "Stable", 1, 3000, "HEALTHY", 1000L);
        ConsumerGroupSummaryDto failedGroupT1 = new ConsumerGroupSummaryDto("failed-group", "Stable", 1, 5000, "HEALTHY", 1000L);

        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L,
                Map.of(clusterSuccessId, List.of(successGroupT1), clusterFailedId, List.of(failedGroupT1)),
                Map.of(clusterSuccessId, 1000L, clusterFailedId, 1000L)
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var response1 = controller.getActiveAlerts();
        List<Map<String, Object>> alerts1 = response1.getBody();
        assertThat(alerts1).hasSize(2);

        Map<String, Object> initialFailedAlert = alerts1.stream()
                .filter(a -> ("consumer-lag-" + clusterFailedId + "-failed-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> initialSuccessAlert = alerts1.stream()
                .filter(a -> ("consumer-lag-" + clusterSuccessId + "-success-group").equals(a.get("id")))
                .findFirst().orElseThrow();

        assertThat(initialFailedAlert.get("createdAt")).isEqualTo(initialSuccessAlert.get("createdAt"));

        // Now: clusterSuccess refreshes at T2 = 2000L with updated group + new group; clusterFailed FAILS and retains T1 data
        ConsumerGroupSummaryDto successGroupT2 = new ConsumerGroupSummaryDto("success-group", "Stable", 1, 8000, "HEALTHY", 2000L);
        ConsumerGroupSummaryDto newSuccessGroupT2 = new ConsumerGroupSummaryDto("new-success-group", "Stable", 1, 4000, "HEALTHY", 2000L);
        ConsumerLagCacheService.AlertCacheGeneration gen2 = new ConsumerLagCacheService.AlertCacheGeneration(
                2L,
                Map.of(clusterSuccessId, List.of(successGroupT2, newSuccessGroupT2), clusterFailedId, List.of(failedGroupT1)),
                Map.of(clusterSuccessId, 2000L, clusterFailedId, 1000L)
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen2);

        var response2 = controller.getActiveAlerts();
        List<Map<String, Object>> alerts2 = response2.getBody();
        assertThat(alerts2).hasSize(3);

        Map<String, Object> updatedSuccessAlert = alerts2.stream()
                .filter(a -> ("consumer-lag-" + clusterSuccessId + "-success-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> newSuccessAlert = alerts2.stream()
                .filter(a -> ("consumer-lag-" + clusterSuccessId + "-new-success-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        Map<String, Object> retainedFailedAlert = alerts2.stream()
                .filter(a -> ("consumer-lag-" + clusterFailedId + "-failed-group").equals(a.get("id")))
                .findFirst().orElseThrow();

        // 1. Existing active alert has updated data (description) while preserving its original creation timestamp
        assertThat(String.valueOf(updatedSuccessAlert.get("description"))).contains("total lag 8000");
        assertThat(updatedSuccessAlert.get("createdAt")).isEqualTo(initialSuccessAlert.get("createdAt"));

        // 2. Newly activated alert gets the updated timestamp contract (2000L)
        assertThat(String.valueOf(newSuccessAlert.get("description"))).contains("total lag 4000");
        assertThat(newSuccessAlert.get("createdAt")).isNotEqualTo(initialSuccessAlert.get("createdAt"));

        // 3. Failed cluster retains last valid data and preserves original createdAt timestamp (1000L)
        assertThat(String.valueOf(retainedFailedAlert.get("description"))).contains("total lag 5000");
        assertThat(retainedFailedAlert.get("createdAt")).isEqualTo(initialFailedAlert.get("createdAt"));
        assertThat(retainedFailedAlert.get("createdAt")).isNotEqualTo(newSuccessAlert.get("createdAt"));
    }

    @Test
    void requestBuildingOlderGenerationCannotOverwriteSnapshotPublishedForNewerGeneration() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "version-conflict-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto groupGen1 = new ConsumerGroupSummaryDto("lag-group", "Stable", 1, 2000, "HEALTHY", 1000L);
        ConsumerGroupSummaryDto groupGen2 = new ConsumerGroupSummaryDto("lag-group", "Stable", 1, 9000, "HEALTHY", 2000L);

        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L, Map.of(clusterId, List.of(groupGen1)), Map.of(clusterId, 1000L)
        );
        ConsumerLagCacheService.AlertCacheGeneration gen2 = new ConsumerLagCacheService.AlertCacheGeneration(
                2L, Map.of(clusterId, List.of(groupGen2)), Map.of(clusterId, 2000L)
        );

        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen2);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        // Populate snapshot with generation 2
        var responseGen2 = controller.getActiveAlerts();
        assertThat(responseGen2.getBody()).anyMatch(a -> String.valueOf(a.get("description")).contains("total lag 9000"));
        assertThat(controller.getSnapshotCacheVersion()).isEqualTo(2L);

        // Now simulate a request that observes older generation 1
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);
        when(consumerLagCacheService.getCacheVersion()).thenReturn(1L);

        controller.getActiveAlerts();

        // The published snapshot must NOT have been downgraded to generation 1
        assertThat(controller.getSnapshotCacheVersion()).isGreaterThanOrEqualTo(2L);
    }

    @Test
    void existingActiveAlertCreationTimestampsAreNotResetOnRepeatedRequests() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "timestamp-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto group = new ConsumerGroupSummaryDto("stable-group", "Stable", 1, 3000, "HEALTHY", 1000L);
        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L, Map.of(clusterId, List.of(group)), Map.of(clusterId, 1000L)
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        var firstResponse = controller.getActiveAlerts();
        Map<String, Object> firstAlert = firstResponse.getBody().stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-stable-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        Object originalCreatedAt = firstAlert.get("createdAt");
        assertThat(originalCreatedAt).isNotNull();

        // Perform multiple subsequent requests (e.g. repeated UI polling)
        for (int i = 0; i < 5; i++) {
            var subsequentResponse = controller.getActiveAlerts();
            Map<String, Object> alert = subsequentResponse.getBody().stream()
                    .filter(a -> ("consumer-lag-" + clusterId + "-stable-group").equals(a.get("id")))
                    .findFirst().orElseThrow();
            // createdAt must remain strictly identical, never reset to Instant.now()
            assertThat(alert.get("createdAt")).isEqualTo(originalCreatedAt);
        }
    }

    @Test
    void reactivatedAlertFollowsLifecycleSemanticsAndUpdatesTimestamp() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "reactivation-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto groupAtT1 = new ConsumerGroupSummaryDto("cycle-group", "Stable", 1, 4000, "HEALTHY", 1000L);
        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L, Map.of(clusterId, List.of(groupAtT1)), Map.of(clusterId, 1000L)
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        // 1. Initial active alert created at T1
        var response1 = controller.getActiveAlerts();
        Map<String, Object> alertT1 = response1.getBody().stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-cycle-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(alertT1.get("status")).isEqualTo("ACTIVE");
        Object createdAtT1 = alertT1.get("createdAt");

        // 2. Alert resolves when lag disappears (empty summaries)
        ConsumerLagCacheService.AlertCacheGeneration gen2 = new ConsumerLagCacheService.AlertCacheGeneration(
                2L, Map.of(clusterId, List.of()), Map.of(clusterId, 2000L)
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen2);
        var response2 = controller.getActiveAlerts();
        Map<String, Object> resolvedAlert = response2.getBody().stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-cycle-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(resolvedAlert.get("status")).isEqualTo("RESOLVED");
        assertThat(resolvedAlert.get("resolvedAt")).isNotNull();

        // 3. Alert reactivates at T3 = 3000L with high lag again
        ConsumerGroupSummaryDto groupAtT3 = new ConsumerGroupSummaryDto("cycle-group", "Stable", 1, 7000, "HEALTHY", 3000L);
        ConsumerLagCacheService.AlertCacheGeneration gen3 = new ConsumerLagCacheService.AlertCacheGeneration(
                3L, Map.of(clusterId, List.of(groupAtT3)), Map.of(clusterId, 3000L)
        );
        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen3);
        var response3 = controller.getActiveAlerts();
        Map<String, Object> reactivatedAlert = response3.getBody().stream()
                .filter(a -> ("consumer-lag-" + clusterId + "-cycle-group").equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(reactivatedAlert.get("status")).isEqualTo("ACTIVE");
        assertThat(reactivatedAlert.get("resolvedAt")).isNull();
        // Reactivated alert updates createdAt to the reactivation timestamp
        assertThat(reactivatedAlert.get("createdAt")).isNotEqualTo(createdAtT1);
    }

    @Test
    void concurrentRequestsDoNotExposePartialAlertsOrLeaveOlderSnapshotPermanentlyPublished() throws Exception {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = createCluster(clusterId, "concurrent-safety-cluster");
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(List.of(cluster));

        ConsumerGroupSummaryDto groupA = new ConsumerGroupSummaryDto("grp-a", "Stable", 1, 2000, "HEALTHY", 1000L);
        ConsumerGroupSummaryDto groupB = new ConsumerGroupSummaryDto("grp-b", "Stable", 1, 3000, "HEALTHY", 1000L);

        ConsumerLagCacheService.AlertCacheGeneration gen1 = new ConsumerLagCacheService.AlertCacheGeneration(
                1L, Map.of(clusterId, List.of(groupA, groupB)), Map.of(clusterId, 1000L)
        );
        ConsumerLagCacheService.AlertCacheGeneration gen2 = new ConsumerLagCacheService.AlertCacheGeneration(
                2L, Map.of(clusterId, List.of(groupA, groupB)), Map.of(clusterId, 2000L)
        );

        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen1);

        AlertController controller = new AlertController(
                alertRepository, clusterRepository, hostRepository, taskRepository,
                hostStatusService, hostParcelRepository, consumerLagCacheService);

        int threadCount = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (idx == 0) {
                        when(consumerLagCacheService.getAlertCacheGeneration()).thenReturn(gen2);
                    }
                    for (int j = 0; j < 30; j++) {
                        var alerts = controller.getActiveAlerts().getBody();
                        assertThat(alerts).isNotNull();
                        boolean hasA = alerts.stream().anyMatch(a -> ("consumer-lag-" + clusterId + "-grp-a").equals(a.get("id")));
                        boolean hasB = alerts.stream().anyMatch(a -> ("consumer-lag-" + clusterId + "-grp-b").equals(a.get("id")));
                        assertThat(hasA).isEqualTo(hasB); // never partial
                    }
                } catch (Throwable t) {
                    exceptions.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        executor.shutdown();

        assertThat(exceptions).isEmpty();
        assertThat(controller.getSnapshotCacheVersion()).isGreaterThanOrEqualTo(1L);
    }
}
