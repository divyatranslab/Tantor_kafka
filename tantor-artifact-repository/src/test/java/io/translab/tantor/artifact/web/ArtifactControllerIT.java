package io.translab.tantor.artifact.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Requires a Docker daemon (Testcontainers spins up PostgreSQL 16). Skipped
 * automatically in environments without Docker.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtifactControllerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("tantor");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        Path repo = Files.createTempDirectory("tantor-it-repo-");
        registry.add("tantor.repository.base-path", repo::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void uploadThenDownloadRoundTrip() throws Exception {
        byte[] payload = "fake-kafka-tarball-bytes".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "kafka_2.13-3.7.0.tgz", "application/gzip", payload);

        String body = mockMvc.perform(multipart("/api/v1/artifacts")
                        .file(file)
                        .param("serviceType", "KAFKA")
                        .param("version", "3.7.0"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("AVAILABLE")))
                .andExpect(jsonPath("$.serviceType", is("KAFKA")))
                .andReturn().getResponse().getContentAsString();

        String id = body.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f\\-]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/artifacts/{id}/download", id))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Checksum-SHA256",
                        org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")))
                .andExpect(header().longValue("Content-Length", payload.length));
    }
}
