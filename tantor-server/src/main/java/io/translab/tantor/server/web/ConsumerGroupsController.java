package io.translab.tantor.server.web;

import io.translab.tantor.server.dto.ConsumerGroupDetailDto;
import io.translab.tantor.server.dto.ConsumerGroupSummaryDto;
import io.translab.tantor.server.dto.PaginatedResponse;
import io.translab.tantor.server.service.ConsumerLagCacheService;
import io.translab.tantor.server.service.CacheRefreshStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/consumer-groups")
public class ConsumerGroupsController {

    private final ConsumerLagCacheService consumerLagCacheService;

    public ConsumerGroupsController(ConsumerLagCacheService consumerLagCacheService) {
        this.consumerLagCacheService = consumerLagCacheService;
    }

    @GetMapping
    public ResponseEntity<?> getConsumerGroups(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "groupId") String sortBy) {
        
        if (page < 0 || size < 1) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "page must be non-negative and size must be positive"));
        }
        if (size > 500) size = 500;

        return ResponseEntity.ok().headers(cacheHeaders(consumerLagCacheService.getCacheStatus(clusterId)))
                .body(consumerLagCacheService.getPaginatedSummaries(clusterId, page, size, search, sortBy));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ConsumerGroupDetailDto> getConsumerGroupDetail(
            @PathVariable UUID clusterId,
            @PathVariable String groupId) {
        
        ConsumerGroupDetailDto detail = consumerLagCacheService.getDetail(clusterId, groupId);
        HttpHeaders headers = cacheHeaders(consumerLagCacheService.getCacheStatus(clusterId));
        return detail == null
                ? ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).build()
                : ResponseEntity.ok().headers(headers).body(detail);
    }

    private HttpHeaders cacheHeaders(CacheRefreshStatus status) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Cache-Status", status.state().name());
        headers.add("X-Cache-Last-Successful-Refresh", Long.toString(status.lastSuccessfulRefreshMillis()));
        headers.add("X-Cache-Refresh-Error", Boolean.toString(status.lastError() != null));
        if (status.lastError() != null) {
            String safeMessage = status.lastError().replaceAll("[\\r\\n\\t]", " ");
            headers.add("X-Cache-Refresh-Error-Message", safeMessage.substring(0, Math.min(200, safeMessage.length())));
        }
        return headers;
    }
}
