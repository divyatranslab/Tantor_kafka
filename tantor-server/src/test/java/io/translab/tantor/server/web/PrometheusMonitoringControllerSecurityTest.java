package io.translab.tantor.server.web;

import io.translab.tantor.server.config.SecurityConfig;
import io.translab.tantor.server.service.PrometheusMonitoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PrometheusMonitoringController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "tantor.security.proxy-secret=test-proxy-secret-for-prometheus")
class PrometheusMonitoringControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PrometheusMonitoringService monitoringService;

    @MockBean
    private JwtDecoder jwtDecoder;

    @Test
    void serviceDiscoveryTargetsAreAvailableWithoutHumanJwt() throws Exception {
        when(monitoringService.prometheusTargets()).thenReturn(List.of());

        mockMvc.perform(get("/internal/prometheus/targets"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(monitoringService).prometheusTargets();
    }
}
