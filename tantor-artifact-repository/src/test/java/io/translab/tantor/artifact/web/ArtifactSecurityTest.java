package io.translab.tantor.artifact.web;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import io.translab.tantor.artifact.audit.ArtifactAuditService;
import io.translab.tantor.artifact.config.SecurityConfig;
import io.translab.tantor.artifact.service.ArtifactService;
import io.translab.tantor.artifact.service.ManifestService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ArtifactController.class, properties = {
        "tantor.security.jwt.audience=tantor-api"
})
@Import({SecurityConfig.class, ArtifactSecurityTest.JwtTestConfig.class})
class ArtifactSecurityTest {

    private static final String ISSUER = "https://issuer.example/realms/Gatekeeper";
    private static final String AUDIENCE = "tantor-api";
    private static final byte[] SECRET =
            "artifact-repository-test-secret-32!".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockBean
    private ArtifactService artifactService;

    @MockBean
    private ManifestService manifestService;

    @MockBean
    private ArtifactAuditService auditService;

    @Test
    void noTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/artifacts/{id}/verify", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unsignedAdminTokenIsRejected() throws Exception {
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("""
                {"sub":"attacker","iss":"%s","aud":["%s"],"roles":["ADMIN"],"exp":4102444800}
                """.formatted(ISSUER, AUDIENCE));

        assertUnauthorized(header + "." + payload + ".");
    }

    @Test
    void modifiedAdminPayloadWithOriginalSignatureIsRejected() throws Exception {
        String monitorToken = token("monitor", "MONITOR", ISSUER, AUDIENCE,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300));
        String[] parts = monitorToken.split("\\.");
        String forgedPayload = base64Url("""
                {"sub":"attacker","iss":"%s","aud":["%s"],"roles":["ADMIN"],"exp":4102444800}
                """.formatted(ISSUER, AUDIENCE));

        assertUnauthorized(parts[0] + "." + forgedPayload + "." + parts[2]);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertUnauthorized(token("admin", "ADMIN", ISSUER, AUDIENCE,
                Instant.now().minusSeconds(600), Instant.now().minusSeconds(300)));
    }

    @Test
    void wrongIssuerIsRejected() throws Exception {
        assertUnauthorized(token("admin", "ADMIN", "https://wrong-issuer.example", AUDIENCE,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300)));
    }

    @Test
    void wrongAudienceIsRejectedWhenAudienceIsConfigured() throws Exception {
        assertUnauthorized(token("admin", "ADMIN", ISSUER, "other-api",
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300)));
    }

    @Test
    void validMonitorCannotInvokeAdminOperation() throws Exception {
        performVerify(token("monitor", "MONITOR", ISSUER, AUDIENCE,
                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300)))
                .andExpect(status().isForbidden());
    }

    @Test
    void validAdminAuthorityFromVerifiedTokenReachesOperation() throws Exception {
        UUID id = UUID.randomUUID();
        when(artifactService.verifyIntegrity(id)).thenReturn(true);

        mockMvc.perform(post("/api/v1/artifacts/{id}/verify", id)
                        .header("Authorization", "Bearer " + token(
                                "admin", "ADMIN", ISSUER, AUDIENCE,
                                Instant.now().minusSeconds(5), Instant.now().plusSeconds(300))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true));

        verify(artifactService).verifyIntegrity(id);
    }

    private void assertUnauthorized(String token) throws Exception {
        performVerify(token).andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions performVerify(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/artifacts/{id}/verify", UUID.randomUUID())
                .header("Authorization", "Bearer " + token));
    }

    private String token(String subject, String role, String issuer, String audience,
                         Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(subject)
                .issuer(issuer)
                .audience(List.of(audience))
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
