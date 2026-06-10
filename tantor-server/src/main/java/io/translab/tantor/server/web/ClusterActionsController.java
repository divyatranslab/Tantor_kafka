package io.translab.tantor.server.web;

import io.translab.tantor.server.service.RollingRestartService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/actions")
@RequiredArgsConstructor
public class ClusterActionsController {

    private final RollingRestartService rollingRestartService;

    @PostMapping("/rolling-restart")
    public ResponseEntity<Map<String, String>> startRollingRestart(@PathVariable UUID clusterId) {
        String taskId = UUID.randomUUID().toString();
        rollingRestartService.executeRollingRestart(clusterId, taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "running"));
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, String>> getTaskStatus(@PathVariable UUID clusterId, @PathVariable String taskId) {
        String status = rollingRestartService.getTaskStatus(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", status));
    }
}
