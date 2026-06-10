package io.translab.tantor.server.web;

import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ui/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final ClusterRepository clusterRepository;
    private final HostRepository hostRepository;
    private final io.translab.tantor.server.repository.AlertRepository alertRepository;
    private final io.translab.tantor.server.repository.ActivityLogRepository activityLogRepository;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getOverviewStats() {
        Map<String, Object> stats = new HashMap<>();
        
        long totalClusters = clusterRepository.count();
        long totalHosts = hostRepository.count();
        long activeAlerts = alertRepository.countByStatus("ACTIVE");

        stats.put("totalClusters", totalClusters);
        stats.put("totalHosts", totalHosts);
        stats.put("activeAlerts", activeAlerts);
        stats.put("healthyClusters", totalClusters); // Placeholder

        return ResponseEntity.ok(stats);
    }

    @GetMapping("/activity")
    public ResponseEntity<List<io.translab.tantor.server.domain.ActivityLog>> getRecentActivity() {
        return ResponseEntity.ok(activityLogRepository.findTop50ByOrderByCreatedAtDesc());
    }
}
