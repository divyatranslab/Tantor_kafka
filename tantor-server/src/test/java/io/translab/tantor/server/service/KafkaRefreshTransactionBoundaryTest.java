package io.translab.tantor.server.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaRefreshTransactionBoundaryTest {

    @Test
    void blockingKafkaRefreshAndOverviewMethodsAreNotWrappedInDatabaseTransactions() throws Exception {
        assertThat(ConsumerLagCacheService.class.getMethod("refreshCaches").getAnnotation(Transactional.class)).isNull();
        assertThat(PartitionCacheService.class.getMethod("refreshCaches").getAnnotation(Transactional.class)).isNull();
        assertThat(ClusterOverviewService.class.getMethod("getOverview", java.util.UUID.class)
                .getAnnotation(Transactional.class)).isNull();
        assertThat(TopicOperationsService.class.getMethod("getTopicConsumers", java.util.UUID.class, String.class)
                .getAnnotation(Transactional.class)).isNull();
    }
}
