package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.service.ExternalClusterService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ExternalClusterController {

    private final ExternalClusterService externalClusterService;
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/api/v1/ui/external-clusters")
    public ResponseEntity<List<Map<String, Object>>> listExternalClusters() {
        return ResponseEntity.ok(externalClusterService.listExternalClusters());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/api/v1/ui/external-clusters/discoveries")
    public ResponseEntity<List<Map<String, Object>>> listPendingDiscoveries() {
        return ResponseEntity.ok(externalClusterService.listPendingDiscoveries());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/api/v1/ui/external-clusters/agents")
    public ResponseEntity<List<Map<String, Object>>> listDiscoveryAgents() {
        return ResponseEntity.ok(externalClusterService.listDiscoveryAgents());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/api/v1/ui/external-clusters/discoveries/{discoveryKey}/inspect")
    public ResponseEntity<Map<String, Object>> inspectDiscovery(@PathVariable String discoveryKey) {
        return ResponseEntity.ok(externalClusterService.inspectDiscovery(discoveryKey));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/external-clusters/bootstrap/test")
    public ResponseEntity<Map<String, Object>> testBootstrap(@RequestBody ExternalClusterService.BootstrapExternalClusterRequest request) {
        return ResponseEntity.ok(externalClusterService.testBootstrap(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/external-clusters/bootstrap/register")
    public ResponseEntity<Map<String, Object>> registerBootstrap(
            
            @RequestBody ExternalClusterService.BootstrapExternalClusterRequest request) {
        ExternalCluster cluster = externalClusterService.registerBootstrapCluster(request);
        return ResponseEntity.ok(Map.of("id", cluster.getId(), "name", cluster.getName()));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'AGENT')")
    @PostMapping("/api/v1/ui/external-clusters/discovery/report")
    public ResponseEntity<Map<String, Object>> reportDiscovery(
            @RequestBody ExternalClusterService.ExternalDiscoveryReport request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        requireAgentAccess(request.getHostId());
        String remoteIp = httpRequest.getRemoteAddr();
        request.setIpAddresses("[\"" + remoteIp + "\"]");
        return ResponseEntity.ok(externalClusterService.recordDiscoveryReport(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/external-clusters/discovery/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeatDiscoveryAgent(
            @RequestBody ExternalClusterService.ExternalDiscoveryReport request,
            jakarta.servlet.http.HttpServletRequest httpRequest) {
        String remoteIp = httpRequest.getRemoteAddr();
        if (request.getIpAddresses() == null || request.getIpAddresses().isBlank()) {
            request.setIpAddresses("[\"" + remoteIp + "\"]");
        }
        return ResponseEntity.ok(externalClusterService.recordDiscoveryAgentHeartbeat(request));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/external-clusters/discoveries/{discoveryKey}/connect")
    public ResponseEntity<Map<String, Object>> connectDiscovery(
            
            @PathVariable String discoveryKey) {
        ExternalCluster cluster = externalClusterService.connectDiscovery(discoveryKey);
        return ResponseEntity.ok(Map.of("id", cluster.getId(), "name", cluster.getName(), "status", "connected"));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/external-clusters/{clusterId}/restart")
    public ResponseEntity<Map<String, Object>> restartExternalCluster(
            
            @PathVariable UUID clusterId) {
        return ResponseEntity.ok(externalClusterService.queueRestart(clusterId));
    }

    private ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.<String, Object>of("error", "Unauthorized"));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/api/v1/ui/external-clusters/tasks/{taskId}")
    public ResponseEntity<Map<String, Object>> getExternalTaskStatus(@PathVariable String taskId) {
        return ResponseEntity.ok(externalClusterService.getExternalTaskStatus(taskId));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR', 'AGENT')")
    @GetMapping("/api/v1/ui/external-clusters/discovery/{clusterName}/tasks")
    public ResponseEntity<Map<String, Object>> pollDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap,
            @RequestParam(required = false) String hostId) {
        requireAgentAccess(hostId);
        return ResponseEntity.ok(externalClusterService.pollAgentTask(clusterName, hostname, bootstrap));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR', 'AGENT')")
    @PostMapping("/api/v1/ui/external-clusters/discovery/{clusterName}/tasks/complete")
    public ResponseEntity<Void> completeDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap,
            @RequestParam(required = false) String hostId,
            @RequestBody(required = false) ExternalClusterService.AgentTaskCompletion completion) {
        requireAgentAccess(hostId);
        externalClusterService.completeAgentTask(
                clusterName,
                hostname,
                bootstrap,
                completion == null ? new ExternalClusterService.AgentTaskCompletion() : completion
        );
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/external-clusters/discovery/{clusterName}/metrics")
    public ResponseEntity<Void> receiveDiscoveryMetrics(
            @PathVariable String clusterName,
            @RequestParam(required = false) String hostId,
            @RequestBody ExternalClusterService.ExternalBrokerMetricsDto metrics) {
        requireAgentAccess(hostId);
        externalClusterService.receiveMetrics(clusterName, metrics);
        return ResponseEntity.ok().build();
    }

    private void requireAgentAccess(String requestHostId) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_AGENT"))) {
            String tokenHostId = auth.getName();
            if (tokenHostId == null || !tokenHostId.equals(requestHostId)) {
                throw new org.springframework.security.access.AccessDeniedException("Agent identity mismatch");
            }
        }
    }

    // Compatibility endpoints for the older discovery-agent build.
    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")
    @GetMapping("/api/v1/ui/clusters/external/{clusterName}/tasks")
    public ResponseEntity<Map<String, Object>> pollLegacyDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap) {
        return pollDiscoveryTask(clusterName, hostname, bootstrap);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/clusters/external/{clusterName}/tasks/complete")
    public ResponseEntity<Void> completeLegacyDiscoveryTask(
            @PathVariable String clusterName,
            @RequestParam String hostname,
            @RequestParam String bootstrap,
            @RequestBody(required = false) ExternalClusterService.AgentTaskCompletion completion) {
        return completeDiscoveryTask(clusterName, hostname, bootstrap, completion);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/api/v1/ui/clusters/external/{clusterName}/tasks/metrics")
    public ResponseEntity<Void> receiveLegacyDiscoveryMetrics(
            @PathVariable String clusterName,
            @RequestBody ExternalClusterService.ExternalBrokerMetricsDto metrics) {
        return receiveDiscoveryMetrics(clusterName, metrics);
    }
}
