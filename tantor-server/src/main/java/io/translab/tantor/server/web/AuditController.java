package io.translab.tantor.server.web;

import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ui/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditService auditService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        Page<AuditLog> result = auditService.search(category, action, status, resourceType, actor, search, from, to, page, size);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("events", result.getContent());
        response.put("page", result.getNumber());
        response.put("size", result.getSize());
        response.put("total", result.getTotalElements());
        response.put("totalPages", result.getTotalPages());
        response.put("summary", auditService.summary());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/integrity")
    public ResponseEntity<Map<String, String>> integrity() {
        return ResponseEntity.ok(Map.of("status", auditService.verifyIntegrity(), "mode", "APPEND_ONLY"));
    }
}
