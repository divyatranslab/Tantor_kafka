package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.service.DeploymentService;
import io.translab.tantor.server.service.HostStatusService;
import io.translab.tantor.server.service.JobService;
import io.translab.tantor.server.domain.Job;
import io.translab.tantor.server.domain.JobType;
import io.translab.tantor.server.domain.JobStatus;
import io.translab.tantor.server.domain.JobStep;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ui/clusters")
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ClusterController {
    private static final int DEFAULT_JMX_EXPORTER_PORT = 7071;

    private final DeploymentService deploymentService;
    private final ClusterRepository clusterRepository;
    private final ExternalClusterRepository externalClusterRepository;
    private final io.translab.tantor.server.repository.ExternalClusterNodeRepository externalClusterNodeRepository;
    private final io.translab.tantor.server.repository.TaskRepository taskRepository;
    private final io.translab.tantor.server.repository.HostRepository hostRepository;
    private final io.translab.tantor.server.repository.HostParcelRepository hostParcelRepository;
    private final io.translab.tantor.server.service.BrokerMetricsCacheService brokerMetricsCacheService;
    private final io.translab.tantor.server.service.ClusterOverviewService clusterOverviewService;
    private final ObjectMapper objectMapper;
    private final io.translab.tantor.server.service.ClusterHealthViewService clusterHealthViewService;
    private final io.translab.tantor.server.service.ClusterDeploymentService clusterDeploymentService;
    private final io.translab.tantor.server.service.ClusterValidationService clusterValidationService;
    private final io.translab.tantor.server.service.ActivityAlertService activityAlertService;
    private final HostStatusService hostStatusService;
    private final io.translab.tantor.server.service.ExternalClusterService externalClusterService;
    private final JobService jobService;
    private final io.translab.tantor.server.audit.AuditService auditService;
    private final io.translab.tantor.server.repository.DiscoveryAgentRepository discoveryAgentRepository;

    @Value("${tantor.artifact-repo.url:http://localhost:8081}")
    private String artifactRepoUrl;



    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")

    @GetMapping
    public List<Map<String, Object>> listClusters() {
        List<Map<String, Object>> result = new ArrayList<>();
        List<io.translab.tantor.server.domain.Cluster> internalClusters = clusterRepository.findByStatusNot("DELETED");
        result.addAll(clusterHealthViewService.mapInternalClusters(internalClusters));

        List<io.translab.tantor.server.domain.ExternalCluster> externalClusters = externalClusterRepository.findByStatusNot("DELETED");
        List<io.translab.tantor.server.domain.DiscoveryAgent> discoveryAgents = discoveryAgentRepository.findAll();
        result.addAll(clusterHealthViewService.mapExternalClusters(externalClusters, discoveryAgents));

        return result;
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getCluster(@PathVariable java.util.UUID id) {
        Optional<io.translab.tantor.server.domain.Cluster> internalOpt = clusterRepository.findById(id);
        if (internalOpt.isPresent() && !"EXTERNAL".equalsIgnoreCase(internalOpt.get().getMode())) {
            return ResponseEntity.ok(clusterHealthViewService.mapInternalCluster(internalOpt.get(), true));
        }
        return externalClusterRepository.findById(id).map(c -> {
            List<io.translab.tantor.server.domain.ExternalClusterNode> nodes = externalClusterNodeRepository.findByClusterId(c.getId());
            return ResponseEntity.ok(clusterHealthViewService.mapExternalCluster(c, nodes, discoveryAgentRepository.findAll(), true));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")

    @PutMapping("/{id}")
    public ResponseEntity<?> updateCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id,
            @RequestBody UpdateClusterRequest request) {
        try {
            return ResponseEntity.ok(clusterDeploymentService.updateCluster(id, request, currentUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")

    @GetMapping("/{id}/tasks")
    public ResponseEntity<List<io.translab.tantor.server.domain.Task>> getClusterTasks(@PathVariable java.util.UUID id) {
        return clusterRepository.findById(id).map(cluster -> {
            List<io.translab.tantor.server.domain.Task> tasks = taskRepository.findByClusterIdOrderByCreatedAtDesc(id);
            return ResponseEntity.ok(tasks);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")

    @PostMapping("/{id}/bind-agent")
    public ResponseEntity<?> bindAgent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID id,
            @RequestBody Map<String, Object> request) {
        String agentIdStr = (String) request.get("agentId");
        if (agentIdStr == null || agentIdStr.isBlank()) return ResponseEntity.badRequest().body(Map.of("error", "Agent ID required"));

        Optional<io.translab.tantor.server.domain.ExternalCluster> extClusterOpt = externalClusterRepository.findById(id);
        if (extClusterOpt.isEmpty()) return ResponseEntity.notFound().build();

        Optional<io.translab.tantor.server.domain.DiscoveryAgent> agentOpt = discoveryAgentRepository.findById(agentIdStr);
        if (agentOpt.isPresent()) {
            io.translab.tantor.server.domain.DiscoveryAgent agent = agentOpt.get();
            agent.setClusterId(id);
            discoveryAgentRepository.save(agent);

            // Also invoke activity log
            activityAlertService.logActivity("INFO", "Bound discovery agent " + agent.getHostname() + " to external cluster " + extClusterOpt.get().getName(), id);

            return ResponseEntity.ok(Map.of("success", true));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Agent not found"));
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")

    @GetMapping("/{id}/brokers")
    public ResponseEntity<Map<String, Object>> getClusterBrokers(@PathVariable java.util.UUID id) {
        Optional<Cluster> clusterOpt = clusterRepository.findById(id);
        if (clusterOpt.isPresent() && !"EXTERNAL".equalsIgnoreCase(clusterOpt.get().getMode())) {
            return clusterOpt.map(cluster -> {
                List<io.translab.tantor.server.dto.BrokerSummaryDto> brokers = brokerMetricsCacheService.getBrokerSummaries(cluster);
                Map<String, Object> response = new HashMap<>();
                response.put("clusterId", cluster.getId());
                response.put("brokers", brokers);
                return ResponseEntity.ok(response);
            }).orElse(ResponseEntity.notFound().build());
        }

        return externalClusterRepository.findById(id).map(extCluster -> {
            List<io.translab.tantor.server.dto.BrokerSummaryDto> brokers = brokerMetricsCacheService.getBrokerSummaries(extCluster);
            Map<String, Object> response = new HashMap<>();
            response.put("clusterId", extCluster.getId());
            response.put("brokers", brokers);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'MONITOR')")

    @GetMapping("/{id}/overview")
    public ResponseEntity<io.translab.tantor.server.dto.ClusterOverviewDto> getClusterOverview(@PathVariable java.util.UUID id) {
        Optional<io.translab.tantor.server.domain.Cluster> clusterOpt = clusterRepository.findById(id);
        if (clusterOpt.isPresent() && !"EXTERNAL".equalsIgnoreCase(clusterOpt.get().getMode())) {
            return ResponseEntity.ok(clusterOverviewService.getOverview(id));
        }

        return externalClusterRepository.findById(id).map(extCluster -> {
            return ResponseEntity.ok(clusterHealthViewService.getExternalClusterOverview(extCluster));
        }).orElse(ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/deploy")
    public ResponseEntity<Map<String, String>> deployCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody DeployClusterRequest request) {
        ResponseEntity<Map<String, String>> validationError = clusterValidationService.validateDeployRequest(request);
        if (validationError != null) {
            return validationError;
        }
        return ResponseEntity.ok(clusterDeploymentService.deployCluster(request, currentUsername()));
    }

    @PreAuthorize("hasRole('ADMIN')")

    @PostMapping("/validate-kraft")
    public ResponseEntity<Map<String, Object>> validateKraftTopology(@RequestBody DeployClusterRequest request) {
        return clusterValidationService.validateKraftTopology(request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/nodes")
    public ResponseEntity<Map<String, String>> addNodesToCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable UUID id,
            @RequestBody DeployClusterRequest request) {
        try {
            return ResponseEntity.ok(clusterDeploymentService.addNodesToCluster(id, request, currentUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }

    @PreAuthorize("hasRole('ADMIN')")

    @PostMapping("/external")
    public ResponseEntity<?> addExternalCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody ExternalClusterRequest request) {
        io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport report =
                new io.translab.tantor.server.service.ExternalClusterService.ExternalDiscoveryReport();
        report.setName(request.getName());
        report.setEnvironment(request.getEnvironment());
        report.setBootstrapServers(request.getBootstrapServers());
        report.setKafkaVersion(request.getKafkaVersion());
        report.setKafkaClusterId(request.getKafkaClusterId());
        report.setKafkaMode(request.getKafkaMode());
        report.setSecurity(request.getSecurity());
        report.setBrokerCount(request.getBrokerCount());
        report.setNodeId(request.getNodeId());
        report.setRunning(request.isRunning());
        report.setInstallPath(request.getInstallPath());
        report.setLogDirs(request.getLogDirs());
        report.setHostname(request.getHostname());
        return ResponseEntity.ok(externalClusterService.recordDiscoveryReport(report));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID id) {
        try {
            clusterDeploymentService.deleteCluster(id, currentUsername());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/force-delete/{id}")
    public ResponseEntity<Void> forceDeleteCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID id) {
        clusterDeploymentService.forceDeleteCluster(id, currentUsername());
        return ResponseEntity.ok().build();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{id}/upgrade")
    public ResponseEntity<Map<String, String>> upgradeCluster(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable java.util.UUID id,
            @RequestBody UpgradeClusterRequest request) {
        try {
            return ResponseEntity.ok(clusterDeploymentService.upgradeCluster(id, request, currentUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
        }
    }








    @Data
    public static class DeployClusterRequest {
        private String name;
        private String kafka_version;
        private String mode;
        private List<ServiceAssignmentReq> services;
        private Map<String, Object> config;
        private String environment;
        private String artifactUrl;
        private boolean acknowledge_kraft_risk;
    }

    @Data
    static class ExternalClusterRequest {
        private String name;
        private String environment;
        private String bootstrapServers;
        private String kafkaVersion;
        private String kafkaClusterId;
        private String kafkaMode;
        private String security;
        private int brokerCount = 1;
        private Integer nodeId;
        private boolean isRunning = true;
        private String installPath;
        private String logDirs;
        private String hostname;
    }

    @Data
    public static class UpdateClusterRequest {
        private String name;
        private String environment;
    }

    @Data
    public static class UpgradeClusterRequest {
        private String targetVersion;
    }

    @Data
    public static class ServiceAssignmentReq {
        private String host_id;
        private String role;
        private Integer node_id;
        private String configuration_mode;
        private String properties_template;
        private String heap_size;
        private Integer listener_port;
        private Integer controller_port;
        private Integer zookeeper_peer_port;
        private Integer zookeeper_election_port;
    }













































































    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? "" : auth.getName();
    }

}
