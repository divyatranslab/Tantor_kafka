package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cluster-linking")
public class ClusterLinkingController {

    @GetMapping("/links")
    public ResponseEntity<List<Map<String, Object>>> getLinks() {
        // Return an empty list for now until we implement the actual backend logic
        return ResponseEntity.ok(Collections.emptyList());
    }

    @PostMapping("/links")
    public ResponseEntity<?> createLink(@RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of("message", "Link created successfully"));
    }

    @PostMapping("/links/{id}/deploy")
    public ResponseEntity<?> deployLink(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("task_id", "mock-task-123"));
    }

    @PostMapping("/links/{id}/start")
    public ResponseEntity<?> startLink(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }

    @PostMapping("/links/{id}/stop")
    public ResponseEntity<?> stopLink(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/links/{id}")
    public ResponseEntity<?> deleteLink(@PathVariable String id) {
        return ResponseEntity.ok().build();
    }

    @GetMapping("/links/{id}/metrics")
    public ResponseEntity<?> getMetrics(@PathVariable String id) {
        return ResponseEntity.ok(Map.of(
            "link_id", id,
            "link_name", "mock-link",
            "state", "running",
            "connectors", Collections.emptyList(),
            "replication_lag", 0
        ));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<?> getTaskStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(Map.of(
            "status", "completed",
            "logs", List.of("Deployed successfully.")
        ));
    }
}
