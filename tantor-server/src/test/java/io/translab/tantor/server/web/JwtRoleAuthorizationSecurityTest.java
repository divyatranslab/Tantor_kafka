package io.translab.tantor.server.web;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.translab.tantor.server.config.SecurityConfig;
import io.translab.tantor.server.domain.ConfigVersion;
import io.translab.tantor.server.repository.ClusterRepository;
import io.translab.tantor.server.service.ConfigVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConfigVersionController.class)
@Import({SecurityConfig.class, JwtRoleAuthorizationSecurityTest.JwtTestConfig.class})
class JwtRoleAuthorizationSecurityTest {

    private static final String ISSUER = "https://issuer.example/realms/Gatekeeper";
    private static final byte[] SECRET =
            "tantor-server-role-test-secret-32!!".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private ClusterRepository clusterRepository;

    @MockBean
    private ConfigVersionService configVersionService;

    @Test
    void noTokenIsRejected() throws Exception {
        invoke(null).andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedAdminTokenIsRejected() throws Exception {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("""
                {"sub":"attacker","iss":"%s","roles":["ADMIN"],"exp":4102444800}
                """.formatted(ISSUER));

        invoke(header + "." + payload + ".").andExpect(status().isUnauthorized());
    }

    @Test
    void modifiedAdminPayloadWithOriginalSignatureIsRejected() throws Exception {
        String monitorToken = token("monitor", "MONITOR", ISSUER,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300));
        String[] parts = monitorToken.split("\\.");
        String forgedPayload = base64Url("""
                {"sub":"attacker","iss":"%s","roles":["ADMIN"],"exp":4102444800}
                """.formatted(ISSUER));

        invoke(parts[0] + "." + forgedPayload + "." + parts[2])
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        invoke(token("admin", "ADMIN", ISSUER,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        invoke(token("admin", "ADMIN", "https://wrong-issuer.example",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validMonitorCannotInvokeAdminOperation() throws Exception {
        invoke(token("monitor", "MONITOR", ISSUER,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validAdminAuthorityFromVerifiedTokenReachesOperation() throws Exception {
        UUID versionId = UUID.randomUUID();
        ConfigVersion result = new ConfigVersion();
        when(configVersionService.approve(UUID_ZERO, versionId)).thenReturn(result);

        invoke(versionId, token("admin", "ADMIN", ISSUER,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300)))
                .andExpect(status().isOk());

        verify(configVersionService).approve(UUID_ZERO, versionId);
    }

    private static final UUID UUID_ZERO = new UUID(0, 0);

    private org.springframework.test.web.servlet.ResultActions invoke(String token) throws Exception {
        return invoke(UUID.randomUUID(), token);
    }

    private org.springframework.test.web.servlet.ResultActions invoke(UUID versionId, String token)
            throws Exception {
        var request = post("/api/v1/clusters/{clusterId}/config/versions/{versionId}/approve",
                UUID_ZERO, versionId);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    private String token(String subject, String role, String issuer,
                         Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .claim("roles", List.of(role))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @TestConfiguration
    static class JwtTestConfig {

        @Bean
        JwtEncoder jwtEncoder() {
            return new NimbusJwtEncoder(new ImmutableSecret<>(SECRET));
        }

        @Bean
        JwtDecoder jwtDecoder() {
            SecretKey key = new SecretKeySpec(SECRET, "HmacSHA256");
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER)));
            return decoder;
        }
    }
}
