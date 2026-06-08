package io.translab.tantor.server.web;

import io.translab.tantor.server.service.DeploymentService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ui/clusters")
@RequiredArgsConstructor
public class ClusterController {

    private final DeploymentService deploymentService;

    @PostMapping("/deploy")
    public ResponseEntity<Void> deployCluster(@RequestBody DeployClusterRequest request) {
        // Build quorum voters string for KRaft based on the number of hosts
        StringBuilder quorumVoters = new StringBuilder();
        for (int i = 0; i < request.getHosts().size(); i++) {
            if (i > 0) quorumVoters.append(",");
            quorumVoters.append(i + 1).append("@").append(request.getHosts().get(i).getHostname()).append(":9093");
        }
        
        // Dispatch task for each host
        for (int i = 0; i < request.getHosts().size(); i++) {
            HostInfo host = request.getHosts().get(i);
            deploymentService.deployKafkaToHost(
                host.getId(),
                request.getVersion(),
                request.getArtifactUrl(),
                "", // checksum is optional for now
                String.valueOf(i + 1),
                quorumVoters.toString()
            );
        }
        
        return ResponseEntity.ok().build();
    }

    @Data
    static class DeployClusterRequest {
        private String version;
        private String artifactUrl;
        private List<HostInfo> hosts;
    }

    @Data
    static class HostInfo {
        private String id;
        private String hostname;
    }
}
