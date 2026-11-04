package io.translab.tantor.server.web;

import io.translab.tantor.server.dto.PaginatedResponse;
import io.translab.tantor.server.service.CacheRefreshStatus;
import io.translab.tantor.server.service.ConsumerLagCacheService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConsumerGroupsControllerTest {

    @Test
    void rejectsInvalidPaginationWithoutCallingCache() {
        ConsumerLagCacheService cache = mock(ConsumerLagCacheService.class);
        ConsumerGroupsController controller = new ConsumerGroupsController(cache);

        assertThat(controller.getConsumerGroups(UUID.randomUUID(), -1, 10, null, "groupId").getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.getConsumerGroups(UUID.randomUUID(), 0, 0, null, "groupId").getStatusCode().value())
                .isEqualTo(400);
        verifyNoInteractions(cache);
    }

    @Test
    void preservesPaginatedBodyAndAddsCacheFreshnessHeaders() {
        UUID clusterId = UUID.randomUUID();
        ConsumerLagCacheService cache = mock(ConsumerLagCacheService.class);
        PaginatedResponse<?> page = PaginatedResponse.builder().content(List.of()).page(0).size(50)
                .totalElements(0).totalPages(0).hasNext(false).build();
        when(cache.getCacheStatus(clusterId)).thenReturn(new CacheRefreshStatus(CacheRefreshStatus.State.EMPTY,
                1234, 1234, null));
        when(cache.getPaginatedSummaries(clusterId, 0, 50, null, "groupId")).thenReturn((PaginatedResponse) page);

        var response = new ConsumerGroupsController(cache).getConsumerGroups(clusterId, 0, 50, null, "groupId");

        assertThat(response.getBody()).isSameAs(page);
        assertThat(response.getHeaders().getFirst("X-Cache-Status")).isEqualTo("EMPTY");
        assertThat(response.getHeaders().getFirst("X-Cache-Last-Successful-Refresh")).isEqualTo("1234");
        assertThat(response.getHeaders().getFirst("X-Cache-Refresh-Error")).isEqualTo("false");
    }
}
