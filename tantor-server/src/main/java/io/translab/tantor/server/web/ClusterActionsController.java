package io.translab.tantor.server.web;

import io.translab.tantor.server.service.RollingRestartService;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.DeploymentService;
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
    private final DeploymentService deploymentService;
    private final ClusterRepository clusterRepository;

    @PostMapping("/rolling-restart")
    public ResponseEntity<Map<String, String>> startRollingRestart(@PathVariable UUID clusterId) {
        String taskId = UUID.randomUUID().toString();
        rollingRestartService.executeRollingRestart(clusterId, taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", "running"));
    }

    @PostMapping("/normal-restart")
    public ResponseEntity<Map<String, String>> startNormalRestart(@PathVariable UUID clusterId) {
        return clusterRepository.findById(clusterId)
                .map(cluster -> {
                    int count = 0;
                    if (cluster.getServices() != null) {
                        for (var service : cluster.getServices()) {
                            deploymentService.restartService(clusterId, service.getHostId(), systemdServiceName(service.getRole()));
                            count++;
                        }
                    }
                    return ResponseEntity.ok(Map.of(
                            "status", "scheduled",
                            "tasks", String.valueOf(count)
                    ));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<Map<String, String>> getTaskStatus(@PathVariable UUID clusterId, @PathVariable String taskId) {
        String status = rollingRestartService.getTaskStatus(taskId);
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", status));
    }

    private String systemdServiceName(String role) {
        if ("controller".equals(role)) return "controller";
        if ("zookeeper".equals(role)) return "zookeeper";
        if ("broker_controller".equals(role) || "broker_zookeeper".equals(role)) return "kafka";
        return "broker";
    }
}
