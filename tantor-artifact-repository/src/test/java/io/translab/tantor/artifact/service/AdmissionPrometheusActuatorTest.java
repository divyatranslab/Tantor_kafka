package io.translab.tantor.artifact.service;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.translab.tantor.artifact.ArtifactRepositoryApplication;
import io.translab.tantor.artifact.audit.ArtifactAuditRepository;
import io.translab.tantor.artifact.repository.ArtifactDownloadLogRepository;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = ArtifactRepositoryApplication.class,
        properties = {
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "management.endpoints.web.exposure.include=health,info,prometheus",
                "management.prometheus.metrics.export.enabled=true",
                "management.endpoint.health.group.readiness.include=readinessState",
                "tantor.cors.allowed-origins=http://localhost:5173",
                "tantor.repository.base-path=target/test-repo"
        }
)
@AutoConfigureMockMvc
class AdmissionPrometheusActuatorTest {

    @MockBean JdbcTemplate jdbcTemplate;
    @MockBean ArtifactJpaRepository artifactJpaRepository;
    @MockBean ArtifactDownloadLogRepository artifactDownloadLogRepository;
    @MockBean ArtifactAuditRepository artifactAuditRepository;

    @Autowired MockMvc mockMvc;

    @Test
    void metricAppearsInPrometheusScrape() throws Exception {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "validation", 1, 2, Duration.ofSeconds(5));

        controller.registerMetrics(registry);

        String initialScrape = registry.scrape();
        assertThat(initialScrape).contains("tantor_artifact_admission_wait_average_ms");
        assertThat(initialScrape).contains("controller=\"validation\"");

        // Occupy slot, queue a waiter, and admit it
        BoundedAdmissionController.Lease lease1 = controller.acquire();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);

        try {
            Future<?> future = executor.submit(() -> {
                waiterStarted.countDown();
                try (BoundedAdmissionController.Lease lease2 = controller.acquire()) {
                    waiterFinished.countDown();
                }
            });

            assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
            while (controller.getQueuedCount() == 0) {
                Thread.sleep(10);
            }
            Thread.sleep(25);
            lease1.close();

            assertThat(waiterFinished.await(5, TimeUnit.SECONDS)).isTrue();
            future.get(5, TimeUnit.SECONDS);

            String updatedScrape = registry.scrape();
            assertThat(updatedScrape).contains("tantor_artifact_admission_wait_average_ms");
            assertThat(controller.getAverageWaitMs()).isGreaterThan(0.0);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }

    @Test
    void metricAppearsInActuatorPrometheusEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("tantor_artifact_admission_wait_average_ms")));
    }
}
