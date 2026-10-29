package io.translab.tantor.server.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeEnvironmentPropertiesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "", "   ", "${TANTOR_ENVIRONMENT}", "\"${TANTOR_ENVIRONMENT}\"",
            "'$TANTOR_ENVIRONMENT'", "$TANTOR_ENVIRONMENT", "null", "NULL", "undefined"
    })
    void fromStringReturnsNullForBlankAndUnresolvedPlaceholders(String placeholder) {
        assertThat(RuntimeEnvironmentProperties.Environment.fromString(placeholder)).isNull();
    }

    @Test
    void fromStringHandlesNullInput() {
        assertThat(RuntimeEnvironmentProperties.Environment.fromString(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = { "dev", "DEV", "development", "DEVELOPMENT" })
    void fromStringResolvesDevelopmentAliases(String value) {
        assertThat(RuntimeEnvironmentProperties.Environment.fromString(value))
                .isEqualTo(RuntimeEnvironmentProperties.Environment.DEVELOPMENT);
    }

    @ParameterizedTest
    @ValueSource(strings = { "sit", "SIT" })
    void fromStringResolvesSit(String value) {
        assertThat(RuntimeEnvironmentProperties.Environment.fromString(value))
                .isEqualTo(RuntimeEnvironmentProperties.Environment.SIT);
    }

    @ParameterizedTest
    @ValueSource(strings = { "uat", "UAT" })
    void fromStringResolvesUat(String value) {
        assertThat(RuntimeEnvironmentProperties.Environment.fromString(value))
                .isEqualTo(RuntimeEnvironmentProperties.Environment.UAT);
    }

    @ParameterizedTest
    @ValueSource(strings = { "prod", "PROD", "production", "PRODUCTION" })
    void fromStringResolvesProductionAliases(String value) {
        assertThat(RuntimeEnvironmentProperties.Environment.fromString(value))
                .isEqualTo(RuntimeEnvironmentProperties.Environment.PRODUCTION);
    }

    @Test
    void fromStringThrowsOnInvalidEnvironment() {
        assertThatThrownBy(() -> RuntimeEnvironmentProperties.Environment.fromString("staging"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stringConverterDelegatesToFromString() {
        var converter = new RuntimeEnvironmentProperties.StringToEnvironmentConverter();
        assertThat(converter.convert("${TANTOR_ENVIRONMENT}")).isNull();
        assertThat(converter.convert("dev")).isEqualTo(RuntimeEnvironmentProperties.Environment.DEVELOPMENT);
    }

    @Test
    void derivesEnvironmentFromActiveSpringProfileWhenUnassigned() {
        RuntimeEnvironmentProperties properties = new RuntimeEnvironmentProperties();
        MockEnvironment mockEnv = new MockEnvironment();
        mockEnv.setActiveProfiles("uat");

        properties.setEnvironment(mockEnv);
        assertThat(properties.getEnvironment()).isEqualTo(RuntimeEnvironmentProperties.Environment.UAT);
        assertThat(properties.isProductionLike()).isTrue();

        // Null should not overwrite active profile derivation
        properties.setEnvironment((RuntimeEnvironmentProperties.Environment) null);
        assertThat(properties.getEnvironment()).isEqualTo(RuntimeEnvironmentProperties.Environment.UAT);
    }

    @Test
    void explicitEnvironmentTakesPrecedenceOverSpringProfile() {
        RuntimeEnvironmentProperties properties = new RuntimeEnvironmentProperties();
        MockEnvironment mockEnv = new MockEnvironment();
        mockEnv.setActiveProfiles("dev");

        properties.setEnvironment(mockEnv);
        properties.setEnvironment(RuntimeEnvironmentProperties.Environment.PRODUCTION);
        assertThat(properties.getEnvironment()).isEqualTo(RuntimeEnvironmentProperties.Environment.PRODUCTION);
        assertThat(properties.isProductionLike()).isTrue();
    }
}
