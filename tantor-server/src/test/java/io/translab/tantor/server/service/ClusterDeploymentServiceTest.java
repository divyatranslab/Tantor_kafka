package io.translab.tantor.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.service.ActivityAlertService;
import io.translab.tantor.server.audit.AuditService;
import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.HostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class ClusterDeploymentServiceTest {

    private ClusterRepository clusterRepository;
    private HostRepository hostRepository;
    private JobService jobService;
    private ActivityAlertService activityAlertService;
    private ClusterValidationService clusterValidationService;
    private DeploymentService deploymentService;
    private ObjectMapper objectMapper;
    private ClusterDeploymentService clusterDeploymentService;

    @BeforeEach
    void setUp() {
        clusterRepository = mock(ClusterRepository.class);
        hostRepository = mock(HostRepository.class);
        jobService = mock(JobService.class);
        activityAlertService = mock(ActivityAlertService.class);
        clusterValidationService = mock(ClusterValidationService.class);
        deploymentService = mock(DeploymentService.class);
        objectMapper = new ObjectMapper();

        clusterDeploymentService = new ClusterDeploymentService(
                clusterRepository,
                hostRepository,
                jobService,
                activityAlertService,
                clusterValidationService,
                deploymentService,
                objectMapper,
                "http://localhost:8081"
        );
    }

    @Test
    void testUpdateClusterNotFound() {
        UUID clusterId = UUID.randomUUID();
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            clusterDeploymentService.updateCluster(clusterId, null, "admin");
        });
    }

    @Test
    void testDeleteClusterNotFound() {
        UUID clusterId = UUID.randomUUID();
        when(clusterRepository.findById(clusterId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> {
            clusterDeploymentService.deleteCluster(clusterId, "admin");
        });
    }
}
