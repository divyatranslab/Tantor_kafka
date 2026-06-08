package io.translab.tantor.artifact;

import io.translab.tantor.artifact.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

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

    public static void main(String[] args) {
        SpringApplication.run(ArtifactRepositoryApplication.class, args);
    }
}
