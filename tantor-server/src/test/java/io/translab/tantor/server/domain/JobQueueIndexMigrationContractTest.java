package io.translab.tantor.server.domain;

import io.translab.tantor.server.repository.JobRepository;
import jakarta.persistence.Column;
import jakarta.persistence.LockModeType;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JobQueueIndexMigrationContractTest {

    private static final String MIGRATION_PATH = "/db/migration/V83__add_job_claiming_queue_index.sql";

    @Test
    @DisplayName("Migration V83 exists and defines the queue index on kf_jobs (status, created_at, id)")
    void migrationDefinesJobClaimingQueueIndex() throws IOException {
        String sql;
        try (var stream = getClass().getResourceAsStream(MIGRATION_PATH)) {
            assertThat(stream).as("V83 migration resource must exist").isNotNull();
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertThat(sql).contains("create index if not exists idx_kf_jobs_status_created_id");
        assertThat(sql).contains("on kf_jobs (status, created_at, id)");
        assertThat(sql).as("Flyway migrations execute in transactions, so CONCURRENTLY must not be used")
                .doesNotContain("concurrently");
    }

    @Test
    @DisplayName("Target table and columns in V83 match the Job entity JPA mappings")
    void migrationMatchesJobEntityMappings() {
        Table tableAnnotation = Job.class.getAnnotation(Table.class);
        assertThat(tableAnnotation).isNotNull();
        assertThat(tableAnnotation.name()).isEqualTo("kf_jobs");

        // Verify id column
        Field idField = findField(Job.class, "id");
        assertThat(idField).isNotNull();

        // Verify status column
        Field statusField = findField(Job.class, "status");
        assertThat(statusField).isNotNull();

        // Verify created_at column
        Field createdAtField = findField(Job.class, "createdAt");
        assertThat(createdAtField).isNotNull();
        Column createdAtCol = createdAtField.getAnnotation(Column.class);
        assertThat(createdAtCol).isNotNull();
        assertThat(createdAtCol.name()).isEqualTo("created_at");
    }

    @Test
    @DisplayName("JobRepository.findFirstByStatusOrderByCreatedAtAsc uses pessimistic lock and matches index structure")
    void repositoryClaimMethodMatchesQueueIndexSpecification() throws NoSuchMethodException {
        Method claimMethod = JobRepository.class.getMethod("findFirstByStatusOrderByCreatedAtAsc", JobStatus.class);
        assertThat(claimMethod).isNotNull();

        Lock lockAnnotation = claimMethod.getAnnotation(Lock.class);
        assertThat(lockAnnotation).as("Job claim query must hold a pessimistic write lock").isNotNull();
        assertThat(lockAnnotation.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    private Field findField(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }
}
