package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/schema-registry")
public class SchemaRegistryController {

    @GetMapping("/{clusterId}/health")
    public ResponseEntity<?> getHealth(@PathVariable String clusterId) {
        return ResponseEntity.ok(Map.of(
            "reachable", true,
            "url", "http://localhost:8081",
            "subject_count", 0
        ));
    }

    @GetMapping("/{clusterId}/subjects")
    public ResponseEntity<List<String>> getSubjects(@PathVariable String clusterId) {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/{clusterId}/compatibility")
    public ResponseEntity<?> getCompatibility(@PathVariable String clusterId) {
        return ResponseEntity.ok(Map.of("compatibility", "BACKWARD"));
    }

    @PutMapping("/{clusterId}/compatibility")
    public ResponseEntity<?> setCompatibility(@PathVariable String clusterId, @RequestBody Map<String, String> payload) {
        return ResponseEntity.ok(payload);
    }

    @GetMapping("/{clusterId}/subjects/{subject}/versions")
    public ResponseEntity<List<Integer>> getVersions(@PathVariable String clusterId, @PathVariable String subject) {
        return ResponseEntity.ok(Collections.emptyList());
    }

    @GetMapping("/{clusterId}/subjects/{subject}/versions/{version}")
    public ResponseEntity<?> getVersion(@PathVariable String clusterId, @PathVariable String subject, @PathVariable String version) {
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{clusterId}/subjects/{subject}/versions")
    public ResponseEntity<?> registerSchema(@PathVariable String clusterId, @PathVariable String subject, @RequestBody Map<String, Object> payload) {
        return ResponseEntity.ok(Map.of("id", 1));
    }

    @DeleteMapping("/{clusterId}/subjects/{subject}")
    public ResponseEntity<?> deleteSubject(@PathVariable String clusterId, @PathVariable String subject) {
        return ResponseEntity.ok().build();
    }
}
