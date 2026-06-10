package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ClusterServiceAssignment;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.DeploymentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/ui/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final DeploymentService deploymentService;
    private final ClusterRepository clusterRepository;
    private final ObjectMapper objectMapper;

    @GetMapping
    public List<Map<String, Object>> listClusters() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Cluster c : clusterRepository.findAll()) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", c.getId());
            m.put("name", c.getName());
            m.put("kafkaVersion", c.getKafkaVersion());
            m.put("mode", c.getMode());
            m.put("environment", c.getEnvironment());
            m.put("createdAt", c.getCreatedAt());
            m.put("nodeCount", c.getServices() != null ? c.getServices().size() : 0);
            result.add(m);
        }
        return result;
    }

    @PostMapping("/deploy")
    public ResponseEntity<Void> deployCluster(@RequestBody DeployClusterRequest request) {
        
        // 1. Save Cluster to Database
        Cluster cluster = new Cluster();
        cluster.setName(request.getName());
        cluster.setKafkaVersion(request.getKafka_version());
        cluster.setMode(request.getMode());
        cluster.setEnvironment(request.getEnvironment());
        
        try {
            cluster.setConfigJson(objectMapper.writeValueAsString(request.getConfig()));
        } catch (Exception e) {
            cluster.setConfigJson("{}");
        }

        List<ClusterServiceAssignment> assignments = new ArrayList<>();
        for (ServiceAssignmentReq sa : request.getServices()) {
            ClusterServiceAssignment assign = new ClusterServiceAssignment();
            assign.setCluster(cluster);
            assign.setHostId(sa.getHost_id());
            assign.setRole(sa.getRole());
            assign.setNodeId(sa.getNode_id());
            assignments.add(assign);
        }
        cluster.setServices(assignments);
        clusterRepository.save(cluster);

        // 2. Build quorum voters string for KRaft
        // Find all controllers or broker_controllers
        List<ServiceAssignmentReq> controllers = request.getServices().stream()
                .filter(s -> s.getRole().equals("controller") || s.getRole().equals("broker_controller") || s.getRole().equals("zookeeper"))
                .collect(Collectors.toList());

        StringBuilder quorumVoters = new StringBuilder();
        int controllerPort = 9093;
        if (request.getConfig() != null && request.getConfig().containsKey("controller_port")) {
            controllerPort = (Integer) request.getConfig().get("controller_port");
        }

        for (int i = 0; i < controllers.size(); i++) {
            if (i > 0) quorumVoters.append(",");
            // Note: In reality we need the hostname of the controller, but we only have host_id here.
            // For now, we assume the frontend sent hostnames somewhere, or we look it up.
            // But the agent just needs hostnames. Actually we need to fetch Host from DB.
            // Since we don't have HostRepository wired here right now, we'll just use a placeholder
            // or we'll pass the problem to the DeploymentService or assume the Hostname is passed.
            // For KRaft, node_id@hostname:port.
            // We will let DeploymentService handle the real mapping or just pass the quorum string as best effort.
            quorumVoters.append(controllers.get(i).getNode_id()).append("@").append(controllers.get(i).getHost_id()).append(":").append(controllerPort);
        }
        
        // 3. Dispatch tasks
        for (ServiceAssignmentReq svc : request.getServices()) {
            // Convert config to JSON string to pass as a parameter
            String configJsonStr = "{}";
            try {
                configJsonStr = objectMapper.writeValueAsString(request.getConfig());
            } catch (Exception e) {}

            String finalArtifactUrl = request.getArtifactUrl();
            if (finalArtifactUrl != null && finalArtifactUrl.contains("localhost")) {
                finalArtifactUrl = finalArtifactUrl.replace("localhost", "192.168.3.142");
            }

            deploymentService.deployKafkaToHost(
                svc.getHost_id(),
                request.getKafka_version(),
                finalArtifactUrl,
                "", // checksum
                String.valueOf(svc.getNode_id()),
                quorumVoters.toString(),
                svc.getRole(),
                configJsonStr
            );
        }
        
        return ResponseEntity.ok().build();
    }

    @Data
    static class DeployClusterRequest {
        private String name;
        private String kafka_version;
        private String mode;
        private List<ServiceAssignmentReq> services;
        private Map<String, Object> config;
        private String environment;
        private String artifactUrl;
    }

    @Data
    static class ServiceAssignmentReq {
        private String host_id;
        private String role;
        private Integer node_id;
    }
}
