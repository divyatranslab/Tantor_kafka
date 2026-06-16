package io.translab.tantor.server.web;

import io.translab.tantor.server.service.KafkaAdminService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}")
@RequiredArgsConstructor
public class TopicsController {

    private final KafkaAdminService kafkaAdminService;
    private final io.translab.tantor.server.service.PartitionCacheService partitionCacheService;

    @GetMapping("/topics")
    public ResponseEntity<io.translab.tantor.server.dto.PaginatedResponse<io.translab.tantor.server.dto.TopicSummaryDto>> listTopics(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "name") String sortBy) {
        
        // Prevent huge page requests
        if (size > 500) size = 500;
        
        return ResponseEntity.ok(kafkaAdminService.listTopicsPaginated(clusterId, page, size, search, sortBy));
    }

    @PostMapping("/topics")
    public ResponseEntity<Void> createTopic(@PathVariable UUID clusterId, @RequestBody TopicCreateRequest request) {
        kafkaAdminService.createTopic(
            clusterId, 
            request.getName(), 
            request.getPartitions(), 
            request.getReplicationFactor(), 
            request.getConfigs()
        );
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/topics/{topicName}")
    public ResponseEntity<Void> deleteTopic(@PathVariable UUID clusterId, @PathVariable String topicName) {
        kafkaAdminService.deleteTopic(clusterId, topicName);
        return ResponseEntity.ok().build();
    }


    @GetMapping("/partitions")
    public ResponseEntity<io.translab.tantor.server.dto.PaginatedResponse<io.translab.tantor.server.dto.PartitionSummaryDto>> listPartitions(
            @PathVariable UUID clusterId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "topicName") String sortBy) {
        
        if (size > 500) size = 500;
        return ResponseEntity.ok(partitionCacheService.getPaginatedPartitions(clusterId, page, size, search, sortBy));
    }

    @Data
    public static class TopicCreateRequest {
        private String name;
        private int partitions = 1;
        private short replicationFactor = 1;
        private Map<String, String> configs;
    }
}
