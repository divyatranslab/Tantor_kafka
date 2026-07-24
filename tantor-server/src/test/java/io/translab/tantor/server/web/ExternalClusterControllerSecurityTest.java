package io.translab.tantor.server.web;

import io.translab.tantor.server.service.ExternalClusterService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.translab.tantor.server.config.SecurityConfig;

@WebMvcTest(ExternalClusterController.class)
@Import(SecurityConfig.class)
public class ExternalClusterControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExternalClusterService externalClusterService;



    @Test
    @WithMockUser(username = "agent-123", authorities = {"ROLE_AGENT"})
    public void testDiscoveryReportWithCorrectIdentity() throws Exception {
        String payload = "{\"hostId\":\"agent-123\", \"clusterName\":\"test\"}";
        mockMvc.perform(post("/api/v1/ui/external-clusters/discovery/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "agent-123", authorities = {"ROLE_AGENT"})
    public void testDiscoveryReportWithWrongIdentity() throws Exception {
        String payload = "{\"hostId\":\"agent-456\", \"clusterName\":\"test\"}";
        mockMvc.perform(post("/api/v1/ui/external-clusters/discovery/report")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "agent-123", authorities = {"ROLE_AGENT"})
    public void testPollTasksWithCorrectIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/ui/external-clusters/discovery/test-cluster/tasks")
                .param("hostname", "host1")
                .param("bootstrap", "broker1:9092")
                .param("hostId", "agent-123"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "agent-123", authorities = {"ROLE_AGENT"})
    public void testPollTasksWithWrongIdentity() throws Exception {
        mockMvc.perform(get("/api/v1/ui/external-clusters/discovery/test-cluster/tasks")
                .param("hostname", "host1")
                .param("bootstrap", "broker1:9092")
                .param("hostId", "agent-456"))
                .andExpect(status().isForbidden());
    }
}
