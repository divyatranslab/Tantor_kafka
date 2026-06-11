package io.translab.tantor.server.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {

    @GetMapping
    public ResponseEntity<?> getActivity(
            @RequestParam(required = false) String cluster_id,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        
        return ResponseEntity.ok(Map.of(
            "entries", Collections.emptyList(),
            "count", 0
        ));
    }
}
