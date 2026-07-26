package io.translab.tantor.server.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretConfigurationValidatorTest {

    private static final String DB = "Db_9Yp4vQ2mL8x";
    private static final String ENCRYPTION = "Enc_7gR2mK9pL4vN8xQ6tB3wC5zH1sJ0";
    private static final String SALT = "Salt_8qL2nR5vT9x";
    private static final String JWT = "Jwt_6mQ9vB2xR8pL4nT7wC5zK1sH3gF0";
    private static final String PROXY = "Proxy_9vL3xQ7mR2nT8pB5wC1zK6sH4gF0";
    private static final String MONITORING = "Grafana_7vQ2mL9x";
    private static final String KEYSTORE = "Store_8pL3vN7x";

    @Test
    void acceptsStrongConfiguration() {
        new SecretConfigurationValidator(DB, ENCRYPTION, SALT, JWT, PROXY, MONITORING, KEYSTORE);
    }

    @Test
    void rejectsMissingJwtSecretWithoutExposingOtherValues() {
        assertSafeFailure("TANTOR_JWT_SECRET",
                () -> new SecretConfigurationValidator(DB, ENCRYPTION, SALT, "", PROXY, MONITORING, KEYSTORE));
    }

    @Test
    void rejectsMissingEncryptionKey() {
        assertSafeFailure("TANTOR_ENCRYPTION_KEY",
                () -> new SecretConfigurationValidator(DB, "", SALT, JWT, PROXY, MONITORING, KEYSTORE));
    }

    @Test
    void rejectsMissingDatabasePassword() {
        assertSafeFailure("TANTOR_DB_PASSWORD",
                () -> new SecretConfigurationValidator("", ENCRYPTION, SALT, JWT, PROXY, MONITORING, KEYSTORE));
    }

    @Test
    void rejectsWeakPlaceholder() {
        String weakValue = "CHANGE_ME_MINIMUM_32_CHARACTERS";
        assertThatThrownBy(() -> new SecretConfigurationValidator(
                DB, ENCRYPTION, SALT, weakValue, PROXY, MONITORING, KEYSTORE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TANTOR_JWT_SECRET")
                .hasMessageNotContaining(weakValue);
    }

    private static void assertSafeFailure(String expectedKey, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedKey)
                .satisfies(error -> {
                    assertThat(error.getMessage()).doesNotContain(DB, ENCRYPTION, SALT, JWT, PROXY);
                });
    }
}
