package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/external-clusters")
public class ExternalClustersController {

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> getClusters() {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @PostMapping
    public ResponseEntity<?> createCluster(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of("message", "Cluster created successfully"));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCluster(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of("message", "Cluster updated successfully"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCluster(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/test-unsaved")
    public ResponseEntity<?> testUnsaved(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Test connection successful (mock)"
        ));
    }

    @PostMapping("/{id}/test")
    public ResponseEntity<?> testSaved(@PathVariable String id) {
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Test connection successful (mock)"
        ));
    }

    @GetMapping("/{id}/topics")
    public ResponseEntity<List<Map<String, Object>>> getTopics(@PathVariable String id) {
        return ResponseEntity.ok(Collections.emptyList());
    }
}
