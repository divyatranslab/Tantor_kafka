package io.translab.tantor.server.web;

import io.translab.tantor.server.dto.CentralLogResponseDto;
import io.translab.tantor.server.service.LogManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ui/logs")
@RequiredArgsConstructor
public class LogManagementController {

    private final LogManagementService logManagementService;

    @GetMapping
    public ResponseEntity<CentralLogResponseDto> search(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String hostId,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) UUID clusterId,
            @RequestParam(required = false) Integer retentionDays,
            @RequestParam(required = false) Integer limit) {
        return ResponseEntity.ok(logManagementService.search(query, source, component, hostId, jobId, clusterId, retentionDays, limit));
    }

    @GetMapping("/download")
    public ResponseEntity<String> download(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String hostId,
            @RequestParam(required = false) UUID jobId,
            @RequestParam(required = false) UUID clusterId,
            @RequestParam(required = false) Integer retentionDays,
            @RequestParam(required = false) Integer limit) {
        String body = logManagementService.export(query, source, component, hostId, jobId, clusterId, retentionDays, limit);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("tantor-central-logs-" + Instant.now().toEpochMilli() + ".log")
                        .build()
                        .toString())
                .body(body);
    }
}
