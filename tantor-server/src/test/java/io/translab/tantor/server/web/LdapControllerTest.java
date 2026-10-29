package io.translab.tantor.server.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.server.domain.LdapConfig;
import io.translab.tantor.server.dto.LdapDTOs;
import io.translab.tantor.server.repository.LdapConfigRepository;
import io.translab.tantor.server.security.JwtUtils;
import io.translab.tantor.server.service.LdapService;
import io.translab.tantor.server.util.RoleAuthenticationUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LdapController.class)
@AutoConfigureMockMvc(addFilters = false)
class LdapControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LdapConfigRepository ldapConfigRepository;

    @MockBean
    private LdapService ldapService;

    @MockBean
    private RoleAuthenticationUtil roleAuthenticationUtil;

    @MockBean
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        when(roleAuthenticationUtil.canAccess(any(), eq(RoleAuthenticationUtil.USER_MANAGEMENT))).thenReturn(true);
    }

    @Test
    @DisplayName("PUT /config with valid timeout of 30 seconds saves config and returns 200")
    void testUpdateConfig_validTimeout_savesAndReturnsOk() throws Exception {
        when(ldapConfigRepository.findAll()).thenReturn(Collections.emptyList());
        when(ldapConfigRepository.save(any(LdapConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389",
                    "connectionTimeout": 30
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionTimeout").value(30));

        ArgumentCaptor<LdapConfig> captor = ArgumentCaptor.forClass(LdapConfig.class);
        verify(ldapConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getConnectionTimeout()).isEqualTo(30);
    }

    @Test
    @DisplayName("PUT /config with omitted connectionTimeout defaults to 10 seconds and saves")
    void testUpdateConfig_omittedTimeout_defaultsTo10AndSaves() throws Exception {
        when(ldapConfigRepository.findAll()).thenReturn(Collections.emptyList());
        when(ldapConfigRepository.save(any(LdapConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389"
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionTimeout").value(10));

        ArgumentCaptor<LdapConfig> captor = ArgumentCaptor.forClass(LdapConfig.class);
        verify(ldapConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getConnectionTimeout()).isEqualTo(10);
    }

    @Test
    @DisplayName("PUT /config with lower bound (1 second) saves and returns 200")
    void testUpdateConfig_lowerBoundTimeout_savesAndReturnsOk() throws Exception {
        when(ldapConfigRepository.findAll()).thenReturn(Collections.emptyList());
        when(ldapConfigRepository.save(any(LdapConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389",
                    "connectionTimeout": 1
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionTimeout").value(1));

        ArgumentCaptor<LdapConfig> captor = ArgumentCaptor.forClass(LdapConfig.class);
        verify(ldapConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getConnectionTimeout()).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /config with upper bound (60 seconds) saves and returns 200")
    void testUpdateConfig_upperBoundTimeout_savesAndReturnsOk() throws Exception {
        when(ldapConfigRepository.findAll()).thenReturn(Collections.emptyList());
        when(ldapConfigRepository.save(any(LdapConfig.class))).thenAnswer(invocation -> invocation.getArgument(0));

        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389",
                    "connectionTimeout": 60
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectionTimeout").value(60));

        ArgumentCaptor<LdapConfig> captor = ArgumentCaptor.forClass(LdapConfig.class);
        verify(ldapConfigRepository).save(captor.capture());
        assertThat(captor.getValue().getConnectionTimeout()).isEqualTo(60);
    }

    @Test
    @DisplayName("PUT /config with 0 timeout returns 400 and repository save is NEVER invoked")
    void testUpdateConfig_zeroTimeout_rejectedWithoutSave() throws Exception {
        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389",
                    "connectionTimeout": 0
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verify(ldapConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT /config with negative timeout returns 400 and repository save is NEVER invoked")
    void testUpdateConfig_negativeTimeout_rejectedWithoutSave() throws Exception {
        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389",
                    "connectionTimeout": -5
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verify(ldapConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT /config with timeout above 60 seconds returns 400 and repository save is NEVER invoked")
    void testUpdateConfig_excessiveTimeout_rejectedWithoutSave() throws Exception {
        String json = """
                {
                    "enabled": true,
                    "serverUrl": "ldap://localhost:389",
                    "connectionTimeout": 61
                }
                """;

        mockMvc.perform(put("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest());

        verify(ldapConfigRepository, never()).save(any());
    }

    @Test
    @DisplayName("GET /config returns 200 and includes connectionTimeout in response")
    void testGetConfig_returnsTimeoutInResponse() throws Exception {
        LdapConfig config = new LdapConfig();
        config.setServerUrl("ldap://localhost:389");
        config.setConnectionTimeout(25);
        when(ldapConfigRepository.findAll()).thenReturn(List.of(config));

        mockMvc.perform(get("/api/v1/ldap/config")
                        .header("Authorization", "Bearer token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serverUrl").value("ldap://localhost:389"))
                .andExpect(jsonPath("$.connectionTimeout").value(25));
    }

    @Test
    @DisplayName("POST /test delegates to testConnection when no username provided")
    void testTestConnection_delegatesToService() throws Exception {
        LdapConfig config = new LdapConfig();
        config.setConnectionTimeout(10);
        when(ldapConfigRepository.findAll()).thenReturn(List.of(config));
        when(ldapService.testConnection(eq(config), any())).thenReturn(
                new LdapDTOs.LdapTestResponse(true, "Successfully bound", null, Collections.emptyList()));

        String json = """
                {
                    "bindPassword": "secret"
                }
                """;

        mockMvc.perform(post("/api/v1/ldap/test")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Successfully bound"));

        verify(ldapService).testConnection(eq(config), eq("secret"));
    }

    @Test
    @DisplayName("LdapConfig entity bean validation rejects timeout outside [1, 60]")
    void testLdapConfig_entityValidationEnforcesBounds() {
        jakarta.validation.Validator validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        LdapConfig validConfig = new LdapConfig();
        validConfig.setConnectionTimeout(10);
        assertThat(validator.validate(validConfig)).isEmpty();

        validConfig.setConnectionTimeout(1);
        assertThat(validator.validate(validConfig)).isEmpty();

        validConfig.setConnectionTimeout(60);
        assertThat(validator.validate(validConfig)).isEmpty();

        LdapConfig invalidZero = new LdapConfig();
        invalidZero.setConnectionTimeout(0);
        var zeroViolations = validator.validate(invalidZero);
        assertThat(zeroViolations).hasSize(1);
        assertThat(zeroViolations.iterator().next().getMessage()).contains("at least 1 second");

        LdapConfig invalidAboveMax = new LdapConfig();
        invalidAboveMax.setConnectionTimeout(61);
        var maxViolations = validator.validate(invalidAboveMax);
        assertThat(maxViolations).hasSize(1);
        assertThat(maxViolations.iterator().next().getMessage()).contains("not exceed 60 seconds");
    }

    @Test
    @DisplayName("POST /test returns 200 with sanitized failure message when testConnection fails")
    void testTestConnection_serviceReturnsSanitizedFailure_passedToCallerWithoutLeak() throws Exception {
        LdapConfig config = new LdapConfig();
        when(ldapConfigRepository.findAll()).thenReturn(List.of(config));
        when(ldapService.testConnection(eq(config), any())).thenReturn(
                new LdapDTOs.LdapTestResponse(false, "Connection failed", null, Collections.emptyList()));

        String json = """
                {
                    "bindPassword": "secret"
                }
                """;

        mockMvc.perform(post("/api/v1/ldap/test")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Connection failed"))
                .andExpect(jsonPath("$.userDn").doesNotExist());
    }

    @Test
    @DisplayName("POST /test returns 200 with sanitized failure message when authenticate fails")
    void testTestLogin_serviceReturnsSanitizedFailure_passedToCallerWithoutLeak() throws Exception {
        LdapConfig config = new LdapConfig();
        when(ldapConfigRepository.findAll()).thenReturn(List.of(config));
        when(ldapService.authenticate(eq("alice"), eq("wrongPassword"), eq(config), eq("secret"))).thenReturn(
                new LdapDTOs.LdapTestResponse(false, "Authentication failed.", null, Collections.emptyList()));

        String json = """
                {
                    "username": "alice",
                    "password": "wrongPassword",
                    "bindPassword": "secret"
                }
                """;

        mockMvc.perform(post("/api/v1/ldap/test")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Authentication failed."))
                .andExpect(jsonPath("$.userDn").doesNotExist());
    }
}
