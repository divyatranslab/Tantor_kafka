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

    @GetMapping("/topics")
    public ResponseEntity<List<Map<String, Object>>> listTopics(@PathVariable UUID clusterId) {
        return ResponseEntity.ok(kafkaAdminService.listTopics(clusterId));
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

    @GetMapping("/consumer-groups")
    public ResponseEntity<List<Map<String, Object>>> listConsumerGroups(@PathVariable UUID clusterId) {
        return ResponseEntity.ok(kafkaAdminService.listConsumerGroups(clusterId));
    }

    @Data
    public static class TopicCreateRequest {
        private String name;
        private int partitions = 1;
        private short replicationFactor = 1;
        private Map<String, String> configs;
    }
}
