package io.translab.tantor.server.web;

import io.translab.tantor.server.domain.Cluster;
import io.translab.tantor.server.domain.ExternalCluster;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.repository.DiscoveryAgentRepository;
import io.translab.tantor.server.repository.ExternalClusterNodeRepository;
import io.translab.tantor.server.repository.ExternalClusterRepository;
import io.translab.tantor.server.service.ClusterHealthViewService;
import io.translab.tantor.server.service.ClusterDeploymentService;
import io.translab.tantor.server.service.ClusterOverviewService;
import io.translab.tantor.server.dto.ClusterOverviewDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import io.translab.tantor.server.config.SecurityConfig;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ClusterController.class)
@Import(SecurityConfig.class)
@AutoConfigureMockMvc
public class ClusterControllerTest {


    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ClusterRepository clusterRepository;

    @MockBean
    private ExternalClusterRepository externalClusterRepository;

    @MockBean
    private ExternalClusterNodeRepository externalClusterNodeRepository;

    @MockBean
    private DiscoveryAgentRepository discoveryAgentRepository;

    @MockBean
    private ClusterHealthViewService clusterHealthViewService;

    @MockBean
    private ClusterDeploymentService clusterDeploymentService;

    @MockBean
    private ClusterOverviewService clusterOverviewService;

    // All the other dependencies of ClusterController
    @MockBean
    private io.translab.tantor.server.service.DeploymentService deploymentService;
    @MockBean
    private io.translab.tantor.server.repository.TaskRepository taskRepository;
    @MockBean
    private io.translab.tantor.server.repository.HostRepository hostRepository;
    @MockBean
    private io.translab.tantor.server.repository.HostParcelRepository hostParcelRepository;
    @MockBean
    private io.translab.tantor.server.service.BrokerMetricsCacheService brokerMetricsCacheService;

    @MockBean
    private io.translab.tantor.server.service.ClusterValidationService clusterValidationService;
    @MockBean
    private io.translab.tantor.server.service.ActivityAlertService activityAlertService;
    @MockBean
    private io.translab.tantor.server.service.HostStatusService hostStatusService;
    @MockBean
    private io.translab.tantor.server.service.ExternalClusterService externalClusterService;
    @MockBean
    private io.translab.tantor.server.service.KafkaAdminService kafkaAdminService;
    @MockBean
    private io.translab.tantor.server.service.JobService jobService;
    @MockBean
    private io.translab.tantor.server.audit.AuditService auditService;




    @Test
    @WithMockUser(roles = "ADMIN")
    public void testListClustersAdmin() throws Exception {
        when(clusterRepository.findByStatusNot("DELETED")).thenReturn(Collections.emptyList());
        when(externalClusterRepository.findByStatusNot("DELETED")).thenReturn(Collections.emptyList());
        when(discoveryAgentRepository.findAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/v1/ui/clusters"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testGetInternalCluster() throws Exception {
        UUID id = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(id);
        cluster.setMode("INTERNAL");

        when(clusterRepository.findById(id)).thenReturn(Optional.of(cluster));
        when(clusterHealthViewService.mapInternalCluster(eq(cluster), eq(true)))
                .thenReturn(Map.of("id", id.toString(), "mode", "INTERNAL"));

        mockMvc.perform(get("/api/v1/ui/clusters/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.mode").value("INTERNAL"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testGetExternalCluster() throws Exception {
        UUID id = UUID.randomUUID();
        ExternalCluster cluster = new ExternalCluster();
        cluster.setId(id);

        when(clusterRepository.findById(id)).thenReturn(Optional.empty());
        when(externalClusterRepository.findById(id)).thenReturn(Optional.of(cluster));

        when(clusterHealthViewService.mapExternalCluster(eq(cluster), any(), any(), eq(true))).thenReturn(Map.of("id", id.toString()));

        mockMvc.perform(get("/api/v1/ui/clusters/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testUnknownClusterReturns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterRepository.findById(id)).thenReturn(Optional.empty());
        when(externalClusterRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/ui/clusters/" + id))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testModeExternalRowFallsThrough() throws Exception {
        UUID id = UUID.randomUUID();

        // Internal repo has a row, but mode is EXTERNAL
        Cluster internalRow = new Cluster();
        internalRow.setId(id);
        internalRow.setMode("EXTERNAL");
        when(clusterRepository.findById(id)).thenReturn(Optional.of(internalRow));

        // External repo has the real data
        ExternalCluster extCluster = new ExternalCluster();
        extCluster.setId(id);
        when(externalClusterRepository.findById(id)).thenReturn(Optional.of(extCluster));

        when(clusterHealthViewService.mapExternalCluster(eq(extCluster), any(), any(), eq(true))).thenReturn(Map.of("id", id.toString()));

        mockMvc.perform(get("/api/v1/ui/clusters/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testGetInternalClusterOverview() throws Exception {
        UUID id = UUID.randomUUID();
        Cluster cluster = new Cluster();
        cluster.setId(id);
        cluster.setMode("INTERNAL");

        when(clusterRepository.findById(id)).thenReturn(Optional.of(cluster));

        ClusterOverviewDto dto = new ClusterOverviewDto();
        dto.setClusterId(id);
        when(clusterOverviewService.getOverview(id)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/ui/clusters/" + id + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").value(id.toString()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    public void testGetExternalClusterOverviewSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterRepository.findById(id)).thenReturn(Optional.empty());

        ExternalCluster extCluster = new ExternalCluster();
        extCluster.setId(id);
        when(externalClusterRepository.findById(id)).thenReturn(Optional.of(extCluster));

        ClusterOverviewDto dto = new ClusterOverviewDto();
        dto.setClusterId(id);
        when(clusterHealthViewService.getExternalClusterOverview(extCluster)).thenReturn(dto);

        mockMvc.perform(get("/api/v1/ui/clusters/" + id + "/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clusterId").value(id.toString()));
    }


    // --- Deployment and Lifecycle Tests ---

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testDeployClusterAdmin() throws Exception {
        when(clusterValidationService.validateDeployRequest(any())).thenReturn(null);
        when(clusterDeploymentService.deployCluster(any(), eq("adminUser")))
                .thenReturn(Map.of("id", "123"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/deploy")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testDeployClusterMonitorForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/deploy")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testDeployClusterUnauthenticated() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/deploy")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testUpdateClusterAdminSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterDeploymentService.updateCluster(eq(id), any(), eq("adminUser")))
                .thenReturn(new Cluster());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/ui/clusters/" + id)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testUpdateClusterAdminNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterDeploymentService.updateCluster(eq(id), any(), eq("adminUser")))
                .thenThrow(new IllegalArgumentException("Not found"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/ui/clusters/" + id)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not found"));
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testUpdateClusterMonitorForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/ui/clusters/" + UUID.randomUUID())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testAddNodesToClusterAdminSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterDeploymentService.addNodesToCluster(eq(id), any(), eq("adminUser")))
                .thenReturn(Map.of("jobId", "456"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + id + "/nodes")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testAddNodesToClusterAdminBadRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterDeploymentService.addNodesToCluster(eq(id), any(), eq("adminUser")))
                .thenThrow(new IllegalArgumentException("Bad Request"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + id + "/nodes")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testAddNodesToClusterMonitorForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + UUID.randomUUID() + "/nodes")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testDeleteClusterAdminSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doNothing().when(clusterDeploymentService).deleteCluster(eq(id), eq("adminUser"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/ui/clusters/" + id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testDeleteClusterAdminServerError() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new RuntimeException("Service failure")).when(clusterDeploymentService).deleteCluster(eq(id), eq("adminUser"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/ui/clusters/" + id))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testDeleteClusterMonitorForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/ui/clusters/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testForceDeleteClusterAdminSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        org.mockito.Mockito.doNothing().when(clusterDeploymentService).forceDeleteCluster(eq(id), eq("adminUser"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/force-delete/" + id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testForceDeleteClusterMonitorForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/force-delete/" + UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testUpgradeClusterAdminSuccess() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterDeploymentService.upgradeCluster(eq(id), any(), eq("adminUser")))
                .thenReturn(Map.of("jobId", "789"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + id + "/upgrade")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void testUpgradeClusterAdminBadRequest() throws Exception {
        UUID id = UUID.randomUUID();
        when(clusterDeploymentService.upgradeCluster(eq(id), any(), eq("adminUser")))
                .thenThrow(new IllegalArgumentException("Downgrade not supported"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + id + "/upgrade")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Downgrade not supported"));
    }

    @Test
    @WithMockUser(roles = "MONITOR")
    public void testUpgradeClusterMonitorForbidden() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + UUID.randomUUID() + "/upgrade")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "adminUser", roles = "ADMIN")
    public void csrfDisabled_adminMutationDoesNotRequireCsrfToken() throws Exception {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(clusterDeploymentService.upgradeCluster(eq(id), any(), eq("adminUser")))
                .thenReturn(java.util.Map.of("jobId", "999"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/ui/clusters/" + id + "/upgrade")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isOk());
    }
}
