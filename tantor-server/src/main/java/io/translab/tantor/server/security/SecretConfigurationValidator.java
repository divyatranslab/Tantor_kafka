package io.translab.tantor.server.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Component
public class SecretConfigurationValidator {

    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "changeme", "change_me", "password", "admin", "jayesh123", "defaultsecret", "exampleonly");

    public SecretConfigurationValidator(
            @Value("${spring.datasource.password}") String databasePassword,
            @Value("${tantor.security.encryption.key}") String encryptionKey,
            @Value("${tantor.security.encryption.salt}") String encryptionSalt,
            @Value("${tantor.security.jwt.secret}") String jwtSecret,
            @Value("${tantor.security.proxy-secret}") String proxySecret,
            @Value("${tantor.monitoring.grafana-password}") String monitoringPassword,
            @Value("${server.ssl.key-store-password}") String keyStorePassword) {
        requireStrong("TANTOR_DB_PASSWORD", databasePassword, 12);
        requireStrong("TANTOR_ENCRYPTION_KEY", encryptionKey, 32);
        requireStrong("TANTOR_ENCRYPTION_SALT", encryptionSalt, 16);
        requireStrong("TANTOR_JWT_SECRET", jwtSecret, 32);
        requireStrong("TANTOR_PROXY_SECRET", proxySecret, 32);
        requireStrong("TANTOR_GRAFANA_PASSWORD", monitoringPassword, 16);
        requireStrong("TANTOR_SSL_KEYSTORE_PASSWORD", keyStorePassword, 12);
    }

    static void requireStrong(String configurationKey, String value, int minimumBytes) {
        if (value == null || value.isBlank()) {
            throw invalid(configurationKey, "is required");
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
        if (FORBIDDEN_MARKERS.stream().anyMatch(normalized::contains)) {
            throw invalid(configurationKey, "contains a prohibited placeholder or development default");
        }
        if (value.getBytes(StandardCharsets.UTF_8).length < minimumBytes) {
            throw invalid(configurationKey, "must be at least " + minimumBytes + " bytes");
        }
    }

    private static IllegalStateException invalid(String configurationKey, String reason) {
        return new IllegalStateException("Invalid required secret configuration " + configurationKey + ": " + reason);
    }
}
