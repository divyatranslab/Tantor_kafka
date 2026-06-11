package io.translab.tantor.server.web;

import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ui/clusters/{clusterId}/ksqldb")
public class KsqlDbController {

    @GetMapping("/tables")
    public ResponseEntity<List<Map<String, Object>>> listTables(@PathVariable String clusterId) {
        // Empty mock data for KSQL tables
        return ResponseEntity.ok(new ArrayList<>());
    }

    @GetMapping("/streams")
    public ResponseEntity<List<Map<String, Object>>> listStreams(@PathVariable String clusterId) {
        // Empty mock data for KSQL streams
        return ResponseEntity.ok(new ArrayList<>());
    }

    @PostMapping("/execute")
    public ResponseEntity<Map<String, Object>> executeKsqlRequest(@PathVariable String clusterId, @RequestBody Map<String, Object> request) {
        // Mock execute endpoint
        return ResponseEntity.ok(Map.of("status", "success", "message", "KSQL executed"));
    }
}
