package io.translab.tantor.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIdentityTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter("apb-kafka");

    @Test
    void extractsHostIdFromAgentCertificateCommonName() {
        assertThat(AgentMTLSFilter.extractHostId(
                "O=Translab,OU=Tantor,CN=tantor-agent:host-123"))
                .isEqualTo("host-123");
    }

    @Test
    void rejectsGenericOrMalformedAgentCertificateNames() {
        assertThat(AgentMTLSFilter.extractHostId("CN=tantor-agent,O=Translab")).isNull();
        assertThat(AgentMTLSFilter.extractHostId("not-a-distinguished-name")).isNull();
    }

    @Test
    void authenticatesDiscoveryPathUsingVerifiedProxyIdentity() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/ui/external-clusters/discovery/report");
        request.addHeader("X-Proxy-Secret", "proxy-secret");
        request.addHeader("X-Forwarded-Client-DN",
                "O=Translab,OU=Tantor,CN=tantor-agent:host-123");

        try {
            new AgentMTLSFilter("proxy-secret").doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (servletRequest, servletResponse) -> {
                        assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
                                .isEqualTo("host-123");
                        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                                .extracting("authority")
                                .containsExactly("ROLE_AGENT");
                    });
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void readsRootRealmAndConfiguredClientRoles() {
        Jwt jwt = Jwt.withTokenValue("test")
                .header("alg", "none")
                .subject("user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .claim("roles", List.of("monitor"))
                .claim("realm_access", Map.of("roles", List.of("administrator")))
                .claim("resource_access", Map.of(
                        "apb-kafka", Map.of("roles", List.of("agent")),
                        "unrelated-client", Map.of("roles", List.of("admin"))))
                .build();

        assertThat(converter.convert(jwt))
                .extracting("authority")
                .containsExactlyInAnyOrder("ROLE_MONITOR", "ROLE_ADMIN", "ROLE_AGENT");
    }
}
