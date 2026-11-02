package io.translab.tantor.artifact.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TemporaryStorageBudgetTest {

    private StorageProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private TemporaryStorageBudget budget;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setMaxAggregateTempBytes(1000L);
        meterRegistry = new SimpleMeterRegistry();
        budget = new TemporaryStorageBudget(properties, meterRegistry);
        budget.init();
    }

    @Test
    void reservationSucceedsWithinBudget() {
        TemporaryStorageBudget.Reservation res = budget.reserve(300L, tempDir);
        assertThat(budget.getReservedBytes()).isEqualTo(300L);
        assertThat(budget.getAvailableBudgetBytes()).isEqualTo(700L);

        res.close();
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
        assertThat(budget.getAvailableBudgetBytes()).isEqualTo(1000L);
    }

    @Test
    void reservationRejectionWhenBudgetExhausted() {
        TemporaryStorageBudget.Reservation res1 = budget.reserve(800L, tempDir);
        assertThat(budget.getReservedBytes()).isEqualTo(800L);

        assertThatThrownBy(() -> budget.reserve(300L, tempDir))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Temporary storage budget exhausted");

        assertThat(budget.getRejectedCount()).isEqualTo(1L);
        res1.close();
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
    }

    @Test
    void reservationAdjustmentReflectsActualSize() {
        try (TemporaryStorageBudget.Reservation res = budget.reserve(500L, tempDir)) {
            assertThat(budget.getReservedBytes()).isEqualTo(500L);
            res.adjust(200L);
            assertThat(budget.getReservedBytes()).isEqualTo(200L);
            res.adjust(900L);
            assertThat(budget.getReservedBytes()).isEqualTo(900L);

            assertThatThrownBy(() -> res.adjust(1200L))
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("budget exhausted");
        }
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
    }

    @Test
    void doubleCloseDoesNotDoubleRelease() {
        TemporaryStorageBudget.Reservation res = budget.reserve(400L, tempDir);
        assertThat(budget.getReservedBytes()).isEqualTo(400L);
        res.close();
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
        res.close();
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
    }

    @Test
    void concurrentReservationsEnforceBudgetWithoutOverrun() throws Exception {
        int threads = 8;
        int requests = 20;
        long bytesPerReq = 100L; // Total demand: 2000 bytes, budget: 1000 bytes
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);
        List<TemporaryStorageBudget.Reservation> held = new ArrayList<>();

        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            tasks.add(() -> {
                try {
                    TemporaryStorageBudget.Reservation res = budget.reserve(bytesPerReq, tempDir);
                    synchronized (held) {
                        held.add(res);
                    }
                    successCount.incrementAndGet();
                } catch (UploadLimitExceededException e) {
                    rejectedCount.incrementAndGet();
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        for (Future<Void> f : futures) f.get();
        executor.shutdown();

        // Exactly 10 should succeed (10 * 100 = 1000) and 10 should be rejected
        assertThat(successCount.get()).isEqualTo(10);
        assertThat(rejectedCount.get()).isEqualTo(10);
        assertThat(budget.getReservedBytes()).isEqualTo(1000L);

        // Release all held
        for (TemporaryStorageBudget.Reservation r : held) {
            r.close();
        }
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
    }

    @Test
    void cleanupFailuresTrackedInMetrics() {
        assertThat(budget.getCleanupFailureCount()).isEqualTo(0L);
        budget.recordCleanupFailure();
        assertThat(budget.getCleanupFailureCount()).isEqualTo(1L);

        double count = meterRegistry.get("tantor.artifact.storage.temp.cleanup.failures.total").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void accountExistingTempFilesOnStartup() throws Exception {
        Path existingStaging1 = Files.createFile(tempDir.resolve(".upload-file-1"));
        Path existingStaging2 = Files.createFile(tempDir.resolve(".upload-file-2"));
        Files.write(existingStaging1, new byte[250]);
        Files.write(existingStaging2, new byte[350]);

        budget.accountExistingTempFiles(tempDir);
        assertThat(budget.getReservedBytes()).isEqualTo(600L);
    }

    @Test
    void doubleCloseDoesNotCauseNegativeAccounting() {
        TemporaryStorageBudget.Reservation res = budget.reserve(200L, tempDir);
        assertThat(budget.getReservedBytes()).isEqualTo(200L);

        res.close();
        assertThat(budget.getReservedBytes()).isEqualTo(0L);

        // Second close must be idempotent and not reduce below 0
        res.close();
        assertThat(budget.getReservedBytes()).isEqualTo(0L);
    }

    @Test
    void reservationOverflowThrowsSafely() {
        assertThatThrownBy(() -> budget.reserve(Long.MAX_VALUE, tempDir))
                .isInstanceOf(UploadLimitExceededException.class);
    }
}

