package io.translab.tantor.server.service;

import io.translab.tantor.server.repository.HostRepository;
import io.translab.tantor.server.repository.ClusterRepository;
import org.apache.kafka.clients.admin.ListConsumerGroupsOptions;
import org.apache.kafka.clients.admin.ListConsumerGroupsResult;
import java.util.List;
import java.util.Collection;
import io.translab.tantor.server.domain.Cluster;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RollingRestartHealthServiceTest {

    @Test
    void repeatedHealthSamplesReuseOnlyFreshLagSnapshot() throws Exception {
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        HostRepository hosts = mock(HostRepository.class);
        ClusterRepository clusters = mock(ClusterRepository.class);
        AdminClient admin = mock(AdminClient.class);
        UUID clusterId = UUID.randomUUID();
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);
        ListConsumerGroupsResult groups = mock(ListConsumerGroupsResult.class);
        when(admin.listConsumerGroups(any(ListConsumerGroupsOptions.class))).thenReturn(groups);
        when(groups.all()).thenReturn(KafkaFuture.completedFuture(List.<ConsumerGroupListing>of()));
        ConsumerLagCacheService lagCache = new ConsumerLagCacheService(clusters, kafka, Runnable::run);
        ReflectionTestUtils.setField(lagCache, "refreshTimeoutMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(lagCache, "cacheTtlMillis", TimeUnit.SECONDS.toMillis(30));
        ReflectionTestUtils.setField(lagCache, "retentionTtlMillis", TimeUnit.MINUTES.toMillis(10));
        lagCache.refreshClusterNow(clusterId);
        RollingRestartHealthService service = new RollingRestartHealthService(kafka, hosts, Runnable::run, lagCache);
        ReflectionTestUtils.setField(service, "consumerLagCacheMaxAgeMillis", TimeUnit.SECONDS.toMillis(30));

        assertThat(service.freshCachedConsumerLag(clusterId).orElseThrow()).isZero();
        assertThat(service.freshCachedConsumerLag(clusterId).orElseThrow()).isZero();
        verify(admin, times(1)).listConsumerGroups(any(ListConsumerGroupsOptions.class));

        ReflectionTestUtils.setField(lagCache, "cacheTtlMillis", -1L);
        Object snapshot = ((java.util.Map<?, ?>) ReflectionTestUtils.getField(lagCache, "snapshots")).get(clusterId);
        ReflectionTestUtils.setField(snapshot, "lastSuccessfulRefreshMillis", 1L);
        assertThat(service.freshCachedConsumerLag(clusterId)).isEmpty();
    }

    @Test
    void callerDeadlineBoundsKafkaFutureWaitAndFailsHealthSafely() throws Exception {
        KafkaAdminService kafka = mock(KafkaAdminService.class);
        HostRepository hosts = mock(HostRepository.class);
        AdminClient admin = mock(AdminClient.class);
        DescribeClusterResult clusterResult = mock(DescribeClusterResult.class);
        @SuppressWarnings("unchecked")
        KafkaFuture<java.util.Collection<org.apache.kafka.common.Node>> nodes = mock(KafkaFuture.class);
        UUID clusterId = UUID.randomUUID();
        when(kafka.getAdminClient(clusterId)).thenReturn(admin);
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(clusterResult);
        when(clusterResult.nodes()).thenReturn(nodes);
        when(nodes.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new TimeoutException("slow broker"));

        RollingRestartHealthService service = new RollingRestartHealthService(kafka, hosts, Runnable::run, mock(ConsumerLagCacheService.class));
        ReflectionTestUtils.setField(service, "heartbeatTimeoutSeconds", 90L);

        var snapshot = service.inspect(clusterId, 3, Set.of(), 20);

        assertThat(snapshot.healthy()).isFalse();
        assertThat(snapshot.failures()).containsKey("kafka");
        verify(admin).describeCluster(argThat(options -> options.timeoutMs() > 0 && options.timeoutMs() <= 20));
        verify(nodes).get(anyLong(), eq(TimeUnit.NANOSECONDS));
    }
}
