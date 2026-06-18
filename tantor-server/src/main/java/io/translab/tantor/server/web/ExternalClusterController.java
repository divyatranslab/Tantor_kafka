package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.service.ExternalClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ExternalClusterController {

    private final ExternalClusterService externalClusterService;

    @GetMapping("/api/v1/ui/external-clusters")
    public ResponseEntity<List<Map<String, Object>>> listExternalClusters() {
        return ResponseEntity.ok(externalClusterService.listExternalClusters());
    }

    @GetMapping("/api/v1/ui/external-clusters/discoveries")
    public ResponseEntity<List<Map<String, Object>>> listPendingDiscoveries() {
        return ResponseEntity.ok(externalClusterService.listPendingDiscoveries());
    }

    @PostMapping("/api/v1/ui/external-clusters/bootstrap/test")
    public ResponseEntity<Map<String, Object>> testBootstrap(@RequestBody ExternalClusterService.BootstrapExternalClusterRequest request) {
        return ResponseEntity.ok(externalClusterService.testBootstrap(request.getBootstrapServers()));
    }

    @PostMapping("/api/v1/ui/external-clusters/bootstrap/register")
    public ResponseEntity<Map<String, Object>> registerBootstrap(@RequestBody ExternalClusterService.BootstrapExternalClusterRequest request) {
        Cluster cluster = externalClusterService.registerBootstrapCluster(request);
        return ResponseEntity.ok(Map.of("id", cluster.getId(), "name", cluster.getName()));
    }

    @PostMapping("/api/v1/ui/external-clusters/discovery/report")
    public ResponseEntity<Map<String, Object>> reportDiscovery(@RequestBody ExternalClusterService.ExternalDiscoveryReport request) {
        return ResponseEntity.ok(externalClusterService.recordDiscoveryReport(request));
    }

    @PostMapping("/api/v1/ui/external-clusters/discoveries/{discoveryKey}/connect")
    public ResponseEntity<Map<String, Object>> connectDiscovery(@PathVariable String discoveryKey) {
        Cluster cluster = externalClusterService.connectDiscovery(discoveryKey);
        return ResponseEntity.ok(Map.of("id", cluster.getId(), "name", cluster.getName(), "status", "connected"));
    }

    @PostMapping("/api/v1/ui/external-clusters/{clusterId}/restart")
    public ResponseEntity<Map<String, Object>> restartExternalCluster(@PathVariable UUID clusterId) {
        return ResponseEntity.ok(externalClusterService.queueRestart(clusterId));
    }

    @GetMapping("/api/v1/ui/external-clusters/tasks/{taskId}")
    public ResponseEntity<Map<String, String>> getExternalTaskStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(Map.of("taskId", taskId, "status", externalClusterService.getExternalTaskStatus(taskId)));
    }

    @GetMapping("/api/v1/ui/external-clusters/discovery/{clusterName}/tasks")
    public ResponseEntity<Map<String, String>> pollDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap) {
        return ResponseEntity.ok(externalClusterService.pollAgentTask(clusterName, hostname, bootstrap));
    }

    @PostMapping("/api/v1/ui/external-clusters/discovery/{clusterName}/tasks/complete")
    public ResponseEntity<Void> completeDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap,
            @RequestBody(required = false) ExternalClusterService.AgentTaskCompletion completion) {
        externalClusterService.completeAgentTask(
                clusterName,
                hostname,
                bootstrap,
                completion == null ? new ExternalClusterService.AgentTaskCompletion() : completion
        );
        return ResponseEntity.ok().build();
    }

    @PostMapping("/api/v1/ui/external-clusters/discovery/{clusterName}/metrics")
    public ResponseEntity<Void> receiveDiscoveryMetrics(
            @PathVariable String clusterName,
            @RequestBody ExternalClusterService.ExternalBrokerMetricsDto metrics) {
        externalClusterService.receiveMetrics(clusterName, metrics);
        return ResponseEntity.ok().build();
    }

    // Compatibility endpoints for the older discovery-agent build.
    @GetMapping("/api/v1/ui/clusters/external/{clusterName}/tasks")
    public ResponseEntity<Map<String, String>> pollLegacyDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap) {
        return pollDiscoveryTask(clusterName, hostname, bootstrap);
    }

    @PostMapping("/api/v1/ui/clusters/external/{clusterName}/tasks/complete")
    public ResponseEntity<Void> completeLegacyDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap,
            @RequestBody(required = false) ExternalClusterService.AgentTaskCompletion completion) {
        return completeDiscoveryTask(clusterName, hostname, bootstrap, completion);
    }

    @PostMapping("/api/v1/ui/clusters/external/{clusterName}/tasks/metrics")
    public ResponseEntity<Void> receiveLegacyDiscoveryMetrics(
            @PathVariable String clusterName,
            @RequestBody ExternalClusterService.ExternalBrokerMetricsDto metrics) {
        return receiveDiscoveryMetrics(clusterName, metrics);
    }
}
