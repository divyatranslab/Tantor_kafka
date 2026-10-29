package io.translab.tantor.server.config;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

@Validated
@ConfigurationProperties(prefix = "tantor.runtime")
public class RuntimeEnvironmentProperties implements EnvironmentAware {
    public enum Environment {
        DEVELOPMENT, SIT, UAT, PRODUCTION;

        public static Environment fromString(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                    || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
            if (trimmed.isEmpty() || trimmed.startsWith("$")
                    || "null".equalsIgnoreCase(trimmed) || "undefined".equalsIgnoreCase(trimmed)) {
                return null;
            }
            String normalized = trimmed.toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "DEV", "DEVELOPMENT" -> DEVELOPMENT;
                case "SIT" -> SIT;
                case "UAT" -> UAT;
                case "PROD", "PRODUCTION" -> PRODUCTION;
                default -> Environment.valueOf(normalized);
            };
        }
    }

    @NotNull
    private Environment environment;

    private org.springframework.core.env.Environment springEnv;

    public Environment getEnvironment() {
        if (this.environment == null && this.springEnv != null) {
            deriveEnvironmentFromProfiles();
        }
        return environment;
    }

    public void setEnvironment(Environment environment) {
        if (environment != null) {
            this.environment = environment;
        }
    }

    public boolean isProductionLike() {
        return getEnvironment() != Environment.DEVELOPMENT;
    }

    @Override
    public void setEnvironment(org.springframework.core.env.Environment springEnv) {
        this.springEnv = springEnv;
        if (this.environment == null) {
            deriveEnvironmentFromProfiles();
        }
    }

    private void deriveEnvironmentFromProfiles() {
        if (this.springEnv == null) {
            return;
        }
        Map<String, Environment> supported = Map.of(
                "dev", Environment.DEVELOPMENT,
                "sit", Environment.SIT,
                "uat", Environment.UAT,
                "production", Environment.PRODUCTION);
        Arrays.stream(this.springEnv.getActiveProfiles())
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .filter(supported::containsKey)
                .findFirst()
                .ifPresent(profile -> this.environment = supported.get(profile));
    }

    @Component
    @ConfigurationPropertiesBinding
    public static class StringToEnvironmentConverter implements Converter<String, Environment> {
        @Override
        public Environment convert(String source) {
            return Environment.fromString(source);
        }
    }
}
