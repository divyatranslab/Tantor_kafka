package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/security-scan")
public class SecurityScanController {

    @PostMapping("/clusters/{clusterId}/scan")
    public ResponseEntity<?> scanCluster(@PathVariable String clusterId) {
        Map<String, Object> response = new HashMap<>();
        response.put("cluster_id", clusterId);
        response.put("cluster_name", "Mock Cluster");
        response.put("score", 100);
        response.put("grade", "A");
        response.put("total_checks", 0);
        response.put("passed", 0);
        response.put("failed", 0);
        response.put("critical_issues", 0);
        response.put("high_issues", 0);
        response.put("findings", Collections.emptyList());
        response.put("summary", Collections.emptyMap());
        return ResponseEntity.ok(response);
    }
}
