package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.domain.ConfigVersion;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.ConfigVersionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/clusters/{clusterId}/config")
@RequiredArgsConstructor
public class ConfigVersionController {

    private final ClusterRepository clusterRepository;
    private final ConfigVersionService configVersionService;

    @PostMapping("/services/{serviceId}/versions/preview")
    public ResponseEntity<?> preview(
            @PathVariable UUID clusterId,
            @PathVariable UUID serviceId,
            @RequestBody VersionedConfigRequest request
    ) {
        Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
        if (cluster == null) return ResponseEntity.notFound().build();
        if (findService(cluster, serviceId) == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "Service assignment does not belong to this cluster."));
        }
        return ResponseEntity.ok(configVersionService.preview(
                safeProperties(request.getCurrentProperties()), safeProperties(request.getProperties()), request.isRestart()));
    }

    @PostMapping("/services/{serviceId}/versions")
    public ResponseEntity<?> create(
            @PathVariable UUID clusterId,
            @PathVariable UUID serviceId,
            @RequestBody VersionedConfigRequest request
    ) {
        try {
            Cluster cluster = clusterRepository.findById(clusterId).orElse(null);
            if (cluster == null) return ResponseEntity.notFound().build();
            ClusterServiceAssignment service = findService(cluster, serviceId);
            if (service == null) {
                return ResponseEntity.badRequest().body(Map.of("message", "Service assignment does not belong to this cluster."));
            }
            ConfigVersion version = configVersionService.createVersion(
                    cluster, service, request.getConfigFileName(),
                    safeProperties(request.getCurrentProperties()), safeProperties(request.getProperties()),
                    request.isApprovalRequired(), request.isRestart());
            return ResponseEntity.ok(version);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/versions")
    public ResponseEntity<List<ConfigVersion>> history(
            @PathVariable UUID clusterId,
            @RequestParam(required = false) UUID serviceId
    ) {
        if (!clusterRepository.existsById(clusterId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(configVersionService.history(clusterId, serviceId));
    }

    @PostMapping("/versions/{versionId}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID clusterId, @PathVariable UUID versionId) {
        try {
            return ResponseEntity.ok(configVersionService.approve(clusterId, versionId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/versions/{versionId}/apply")
    public ResponseEntity<?> apply(
            @PathVariable UUID clusterId,
            @PathVariable UUID versionId,
            @RequestBody(required = false) ApplyVersionRequest request
    ) {
        try {
            Job job = configVersionService.apply(clusterId, versionId, request == null || request.isRestart());
            return ResponseEntity.ok(Map.of("jobId", job.getId().toString(), "status", job.getStatus().name()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/versions/{versionId}/rollback")
    public ResponseEntity<?> rollback(@PathVariable UUID clusterId, @PathVariable UUID versionId) {
        try {
            return ResponseEntity.ok(configVersionService.createRollback(clusterId, versionId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private ClusterServiceAssignment findService(Cluster cluster, UUID serviceId) {
        if (cluster.getServices() == null) return null;
        return cluster.getServices().stream().filter(service -> serviceId.equals(service.getId())).findFirst().orElse(null);
    }

    private Map<String, Object> safeProperties(Map<String, Object> properties) {
        return properties == null ? Map.of() : properties;
    }

    @Data
    public static class VersionedConfigRequest {
        private Map<String, Object> currentProperties;
        private Map<String, Object> properties;
        private String configFileName;
        private boolean approvalRequired;
        private boolean restart = true;
    }

    @Data
    public static class ApplyVersionRequest {
        private boolean restart = true;
    }
}
