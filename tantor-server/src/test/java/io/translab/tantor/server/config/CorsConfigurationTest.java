package io.translab.tantor.server.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

public class CorsConfigurationTest {

    @Test
    public void testValidCorsOrigins() {
        SecurityConfig config = new SecurityConfig(null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "http://localhost:5173,https://example.com");
        
        CorsConfigurationSource source = config.corsConfigurationSource();
        
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/ui/dashboard");
        
        CorsConfiguration corsConfig = source.getCorsConfiguration(request);
        assertNotNull(corsConfig);
        assertTrue(corsConfig.getAllowedOrigins().contains("http://localhost:5173"));
        assertTrue(corsConfig.getAllowedOrigins().contains("https://example.com"));
        assertTrue(corsConfig.getAllowCredentials());
    }

    @Test
    public void testWildcardCorsOriginWithCredentialsThrowsException() {
        SecurityConfig config = new SecurityConfig(null);
        ReflectionTestUtils.setField(config, "allowedOrigins", "*");
        
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> {
            config.corsConfigurationSource();
        });
        
        assertTrue(thrown.getMessage().contains("Wildcard CORS origin '*' is not allowed when credentials are true"));
    }
}
