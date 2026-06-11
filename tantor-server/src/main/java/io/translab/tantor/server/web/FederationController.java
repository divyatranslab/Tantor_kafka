package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/federation")
public class FederationController {

    @GetMapping("/overview")
    public ResponseEntity<?> getOverview() {
        return ResponseEntity.ok(Map.of(
            "clusters", Collections.emptyList(),
            "total", 0,
            "managed", 0,
            "external", 0
        ));
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchTopics(@RequestParam String q) {
        return ResponseEntity.ok(Map.of(
            "matches", Collections.emptyList(),
            "match_count", 0,
            "skipped", Collections.emptyList()
        ));
    }
}
