package io.translab.tantor.server.web;

import io.translab.tantor.server.config.SecurityConfig;
import io.translab.tantor.server.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
class AgentControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentService agentService;

    @Test
    @WithMockUser(username = "host-123", authorities = "ROLE_AGENT")
    void matchingCertificateIdentityCanRegister() throws Exception {
        when(agentService.registerHost(any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host_id":"host-123","hostname":"vm-1"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "host-123", authorities = "ROLE_AGENT")
    void certificateCannotRegisterAnotherHost() throws Exception {
        mockMvc.perform(post("/api/v1/agents/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"host_id":"host-456","hostname":"vm-2"}
                                """))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "host-123", authorities = "ROLE_AGENT")
    void certificateCannotPollAnotherHostsTasks() throws Exception {
        mockMvc.perform(get("/api/v1/agents/host-456/tasks"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "host-123", authorities = "ROLE_AGENT")
    void certificateCannotReportAnotherHostsTaskResult() throws Exception {
        mockMvc.perform(post("/api/v1/agents/tasks/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"task_id":"00000000-0000-0000-0000-000000000000",
                                 "host_id":"host-456","status":"SUCCESS"}
                                """))
                .andExpect(status().isForbidden());
    }
}
