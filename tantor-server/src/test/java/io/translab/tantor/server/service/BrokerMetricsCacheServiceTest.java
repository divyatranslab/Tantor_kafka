package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.Host;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.HostRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrokerMetricsCacheServiceTest {

    @Test
    void includesControllerOnlyNodesAndDoesNotInventDiskCapacity() {
        UUID clusterId = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(clusterId);

        ClusterServiceAssignment broker = assignment(cluster, "broker-host", "broker", 1);
        ClusterServiceAssignment controller = assignment(cluster, "controller-host", "controller", 2);
        cluster.setServices(List.of(broker, controller));

        HostRepository hosts = mock(HostRepository.class);
        HostStatusService status = mock(HostStatusService.class);
        Host brokerHost = host("broker-host");
        Host controllerHost = host("controller-host");
        when(hosts.findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any()))
                .thenReturn(List.of(brokerHost, controllerHost));
        when(status.agentConnectivityStatus(brokerHost)).thenReturn("ONLINE");
        when(status.agentConnectivityStatus(controllerHost)).thenReturn("ONLINE");

        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                hosts, mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), monitoringProperties());

        var rows = service.getBrokerSummaries(cluster);

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(row -> {
            assertThat(row.getRole()).isEqualTo("controller");
            assertThat(row.isController()).isTrue();
            assertThat(row.getDiskUsedGb()).isNull();
            assertThat(row.getDiskTotalGb()).isNull();
        });
    }

    @Test
    void loadsHostsForLegacyOverloadInOneBatch() {
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        ClusterServiceAssignment broker = assignment(cluster, "broker-host", "broker", 1);
        ClusterServiceAssignment controller = assignment(cluster, "controller-host", "controller", 2);
        cluster.setServices(List.of(broker, controller));

        HostRepository hosts = mock(HostRepository.class);
        Host brokerHost = host("broker-host");
        Host controllerHost = host("controller-host");
        when(hosts.findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any()))
                .thenReturn(List.of(brokerHost, controllerHost));
        HostStatusService status = mock(HostStatusService.class);
        when(status.agentConnectivityStatus(brokerHost)).thenReturn("OFFLINE");
        when(status.agentConnectivityStatus(controllerHost)).thenReturn("OFFLINE");
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                hosts, mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), monitoringProperties());

        assertThat(service.getBrokerSummaries(cluster)).hasSize(2);

        verify(hosts, times(1)).findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any());
        verify(hosts, never()).findById("broker-host");
        verify(hosts, never()).findById("controller-host");
        service.shutdownMetricsExecutor();
    }

    @Test
    void usesPreloadedHostsWithoutPerServiceRepositoryLookups() {
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        ClusterServiceAssignment broker = assignment(cluster, "broker-host", "broker", 1);

        HostRepository hosts = mock(HostRepository.class);
        HostStatusService status = mock(HostStatusService.class);
        Host brokerHost = host("broker-host");
        when(status.agentConnectivityStatus(brokerHost)).thenReturn("OFFLINE");
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                hosts, mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), monitoringProperties());

        var rows = service.getBrokerSummaries(cluster, List.of(broker), java.util.Map.of("broker-host", brokerHost));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getHostname()).isEqualTo("broker-host");
            assertThat(row.getBrokerHealth()).isEqualTo("OFFLINE");
        });
        verify(hosts, never()).findById("broker-host");
    }

    @Test
    void preloadedCacheIsRefreshedWhenAssignmentsOrHostDataChanges() {
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        ClusterServiceAssignment firstAssignment = assignment(cluster, "host-1", "broker", 1);
        Host firstHost = host("host-1");
        firstHost.setHostname("broker-one");
        firstHost.setCpuUsagePct(12.0);

        HostRepository hosts = mock(HostRepository.class);
        HostStatusService status = mock(HostStatusService.class);
        when(status.agentConnectivityStatus(firstHost)).thenReturn("OFFLINE", "ONLINE");
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                hosts, mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), monitoringProperties());

        var initial = service.getBrokerSummaries(cluster, List.of(firstAssignment),
                java.util.Map.of("host-1", firstHost));
        assertThat(initial).singleElement().satisfies(row -> {
            assertThat(row.getBrokerId()).isEqualTo(1);
            assertThat(row.getHostname()).isEqualTo("broker-one");
        });

        ClusterServiceAssignment changedAssignment = assignment(cluster, "host-2", "controller", 22);
        Host changedHost = host("host-2");
        changedHost.setHostname("controller-two");
        when(status.agentConnectivityStatus(changedHost)).thenReturn("OFFLINE");

        var changed = service.getBrokerSummaries(cluster, List.of(changedAssignment),
                java.util.Map.of("host-2", changedHost));
        assertThat(changed).singleElement().satisfies(row -> {
            assertThat(row.getBrokerId()).isEqualTo(22);
            assertThat(row.getRole()).isEqualTo("controller");
            assertThat(row.getHostname()).isEqualTo("controller-two");
        });

        firstHost.setCpuUsagePct(67.0);
        var changedHostMetrics = service.getBrokerSummaries(cluster, List.of(firstAssignment),
                java.util.Map.of("host-1", firstHost));
        assertThat(changedHostMetrics).singleElement().satisfies(row -> {
            assertThat(row.getHostname()).isEqualTo("broker-one");
            assertThat(row.getCpuUsagePct()).isEqualTo(67.0);
        });
        verify(hosts, never()).findById("host-1");
        verify(hosts, never()).findById("host-2");
    }

    @Test
    void unchangedPreloadedInputsStillUseTheTenSecondCache() {
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        ClusterServiceAssignment assignment = assignment(cluster, "host-1", "broker", 1);
        Host host = host("host-1");

        HostStatusService status = mock(HostStatusService.class);
        when(status.agentConnectivityStatus(host)).thenReturn("OFFLINE", "ONLINE");
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                mock(HostRepository.class), mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), monitoringProperties());
        var preloadedHosts = java.util.Map.of("host-1", host);

        var first = service.getBrokerSummaries(cluster, List.of(assignment), preloadedHosts);
        var cached = service.getBrokerSummaries(cluster, List.of(assignment), preloadedHosts);

        assertThat(first.getFirst().getBrokerHealth()).isEqualTo("OFFLINE");
        assertThat(cached.getFirst().getBrokerHealth()).isEqualTo("OFFLINE");
        verify(status, times(1)).agentConnectivityStatus(host);
    }

    @Test
    void concurrentRefreshesForTheSameInputsShareOneCacheRefresh() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        ClusterServiceAssignment assignment = assignment(cluster, "host-1", "broker", 1);
        Host host = host("host-1");
        CountDownLatch refreshEntered = new CountDownLatch(1);
        CountDownLatch allowRefreshToFinish = new CountDownLatch(1);

        HostStatusService status = mock(HostStatusService.class);
        when(status.agentConnectivityStatus(host)).thenAnswer(invocation -> {
            refreshEntered.countDown();
            assertThat(allowRefreshToFinish.await(5, TimeUnit.SECONDS)).isTrue();
            return "OFFLINE";
        });
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                mock(HostRepository.class), mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), monitoringProperties());
        var preloadedHosts = java.util.Map.of("host-1", host);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> secondCallerThread = new AtomicReference<>();
        CountDownLatch secondCallerReady = new CountDownLatch(1);

        try {
            Future<List<io.translab.tantor.server.dto.BrokerSummaryDto>> first = callers.submit(
                    () -> service.getBrokerSummaries(cluster, List.of(assignment), preloadedHosts));
            assertThat(refreshEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<List<io.translab.tantor.server.dto.BrokerSummaryDto>> second = callers.submit(() -> {
                secondCallerThread.set(Thread.currentThread());
                secondCallerReady.countDown();
                return service.getBrokerSummaries(cluster, List.of(assignment), preloadedHosts);
            });

            assertThat(secondCallerReady.await(5, TimeUnit.SECONDS)).isTrue();
            awaitThreadState(secondCallerThread.get(), Thread.State.BLOCKED);
            allowRefreshToFinish.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).singleElement()
                    .satisfies(row -> assertThat(row.getBrokerHealth()).isEqualTo("OFFLINE"));
            assertThat(second.get(5, TimeUnit.SECONDS)).singleElement()
                    .satisfies(row -> assertThat(row.getBrokerHealth()).isEqualTo("OFFLINE"));
            verify(status, times(1)).agentConnectivityStatus(host);
        } finally {
            allowRefreshToFinish.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void boundsTotalWaitForBrokerMetricsAndReturnsAvailableResults() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setId(UUID.randomUUID());
        ClusterServiceAssignment blockedAssignment = assignment(cluster, "blocked-host", "broker", 1);
        ClusterServiceAssignment availableAssignment = assignment(cluster, "available-host", "broker", 2);
        Host blockedHost = host("blocked-host");
        Host availableHost = host("available-host");
        CountDownLatch blockedFetchStarted = new CountDownLatch(1);
        CountDownLatch releaseBlockedFetch = new CountDownLatch(1);

        HostStatusService status = mock(HostStatusService.class);
        when(status.agentConnectivityStatus(blockedHost)).thenAnswer(invocation -> {
            blockedFetchStarted.countDown();
            try {
                releaseBlockedFetch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "ONLINE";
        });
        when(status.agentConnectivityStatus(availableHost)).thenReturn("OFFLINE");
        var properties = monitoringProperties();
        properties.setBrokerRefreshTimeoutSeconds(1);
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                mock(HostRepository.class), mock(KafkaAdminService.class), mock(ExternalClusterService.class),
                status, new ObjectMapper(), properties);

        long startedAt = System.nanoTime();
        try {
            var rows = service.getBrokerSummaries(
                    cluster,
                    List.of(blockedAssignment, availableAssignment),
                    java.util.Map.of("blocked-host", blockedHost, "available-host", availableHost));
            long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt);

            assertThat(blockedFetchStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(elapsedSeconds).isLessThan(3);
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getHostname()).isEqualTo("available-host");
        } finally {
            releaseBlockedFetch.countDown();
            service.shutdownMetricsExecutor();
        }
    }

    private static void awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && thread.getState() != expected) {
            Thread.sleep(1);
        }
        assertThat(thread.getState()).isEqualTo(expected);
    }

    @Test
    void exposesPersistedExternalIngestionRates() {
        ExternalCluster cluster = new ExternalCluster();
        cluster.setId(UUID.randomUUID());

        ExternalClusterService.ExternalBrokerRecord record =
                new ExternalClusterService.ExternalBrokerRecord();
        record.setNodeId(3);
        record.setHostname("192.168.3.191");
        record.setRole("broker");
        record.setRunning(true);
        record.setMessagesInPerSec(1.67);
        record.setBytesInPerSec(84.81);

        ExternalClusterService externalClusters = mock(ExternalClusterService.class);
        when(externalClusters.brokerRecords(cluster)).thenReturn(List.of(record));
        BrokerMetricsCacheService service = new BrokerMetricsCacheService(
                mock(HostRepository.class), mock(KafkaAdminService.class), externalClusters,
                mock(HostStatusService.class), new ObjectMapper(), monitoringProperties());

        var rows = service.getBrokerSummaries(cluster);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.getMessagesInPerSec()).isEqualTo(1.67);
            assertThat(row.getBytesInPerSec()).isEqualTo(84.81);
        });
    }

    private static io.translab.tantor.server.config.MonitoringProperties monitoringProperties() {
        var properties = new io.translab.tantor.server.config.MonitoringProperties();
        properties.setMode("direct");
        properties.setPrometheusUrl(java.net.URI.create("http://prometheus:9090"));
        return properties;
    }

    private static ClusterServiceAssignment assignment(Cluster cluster, String hostId, String role, int nodeId) {
        ClusterServiceAssignment assignment = new ClusterServiceAssignment();
        assignment.setCluster(cluster);
        assignment.setHostId(hostId);
        assignment.setRole(role);
        assignment.setNodeId(nodeId);
        return assignment;
    }

    private static Host host(String id) {
        Host host = new Host();
        host.setId(id);
        host.setHostname(id);
        return host;
    }
}
