package io.translab.tantor.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtUtilsTest {

    @Test
    void resolvesPreferredUsernameFromKeycloakToken() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JwtUtils jwtUtils = new JwtUtils(mapper);
        String header = encode(mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
        String payload = encode(mapper.writeValueAsBytes(Map.of(
                "preferred_username", "jayesh",
                "email", "jayesh@example.com",
                "exp", Instant.now().plusSeconds(300).getEpochSecond())));

        assertThat(jwtUtils.getIdentityFromJwtToken(header + "." + payload + ".signature"))
                .isEqualTo("jayesh");
    }

    @Test
    void rejectsExpiredKeycloakToken() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JwtUtils jwtUtils = new JwtUtils(mapper);
        String header = encode(mapper.writeValueAsBytes(Map.of("alg", "RS256")));
        String payload = encode(mapper.writeValueAsBytes(Map.of(
                "preferred_username", "old-user",
                "exp", Instant.now().minusSeconds(1).getEpochSecond())));

        assertThat(jwtUtils.getIdentityFromJwtToken(header + "." + payload + ".signature")).isNull();
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}