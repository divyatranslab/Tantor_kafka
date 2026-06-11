package io.translab.tantor.server.web;

import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ui/clusters/{clusterId}/kafka-connect")
public class KafkaConnectController {

    @GetMapping("/clusters")
    public ResponseEntity<List<Map<String, Object>>> listConnectClusters(@PathVariable String clusterId) {
        // Mock data to match kafbat UI screenshots
        List<Map<String, Object>> clusters = new ArrayList<>();
        Map<String, Object> c = new HashMap<>();
        c.put("name", "connect-149");
        c.put("version", "2.8.0");
        c.put("connectors", 0);
        c.put("runningTasks", 0);
        clusters.add(c);
        
        return ResponseEntity.ok(clusters);
    }

    @GetMapping("/connectors")
    public ResponseEntity<List<Map<String, Object>>> listConnectors(@PathVariable String clusterId) {
        // Empty mock data for connectors
        return ResponseEntity.ok(new ArrayList<>());
    }

    @PostMapping("/connectors")
    public ResponseEntity<Void> createConnector(@PathVariable String clusterId, @RequestBody Map<String, Object> request) {
        return ResponseEntity.ok().build();
    }
}
