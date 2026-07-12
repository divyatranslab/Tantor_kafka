package io.translab.tantor.artifact;

import io.translab.tantor.artifact.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Entry point for the Tantor Artifact Repository service (Phase 1).
 *
 * <p>This service is the source of truth for every binary the platform installs
 * onto customer hosts: Kafka, Kafka Connect, Schema Registry, ksqlDB, Cruise
 * Control, Prometheus and Grafana. Tantor agents pull artifacts from here over
 * HTTPS/mTLS and verify the SHA-256 checksum before extracting anything.</p>
 */
@SpringBootApplication
@EnableConfigurationProperties(StorageProperties.class)
public class ArtifactRepositoryApplication {

    @Value("${tantor.cors.allowed-origins:http://localhost:5173,https://192.168.3.191,http://192.168.3.191}")
    private String allowedOrigins;

    public static void main(String[] args) {
        SpringApplication.run(ArtifactRepositoryApplication.class, args);
    }

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(parseAllowedOrigins())
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*");
            }
        };
    }

    private String[] parseAllowedOrigins() {
        return java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
    }
}
