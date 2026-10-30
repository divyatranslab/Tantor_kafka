package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.exception.PackageValidationException;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class PackageValidatorTest {

    @Mock
    MalwareScanner malwareScanner;

    @TempDir
    Path tempDir;

    StorageProperties properties;
    PackageValidator validator;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setMaxConcurrentValidations(2);
        validator = new PackageValidator(properties, malwareScanner);
    }

    @Test
    void validationWaitsInQueueAndProceedsWhenWorkerFinishes() throws Exception {
        properties.setMaxConcurrentValidations(2);
        properties.setMaxQueuedValidations(2);
        properties.setValidationQueueWaitTimeout(Duration.ofSeconds(5));

        CountDownLatch inValidationLatch = new CountDownLatch(2);
        CountDownLatch releaseLatch = new CountDownLatch(1);
        CountDownLatch thirdJobFinished = new CountDownLatch(1);

        SlowInspectValidator slowValidator = new SlowInspectValidator(
                properties, malwareScanner, inValidationLatch, releaseLatch);

        Path archive = createValidKafkaArchive();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> f1 = executor.submit(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"));
            Future<?> f2 = executor.submit(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"));

            assertThat(inValidationLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(slowValidator.getAdmissionController().getActiveCount()).isEqualTo(2);

            // 3rd validation waits in the queue instead of being immediately rejected
            Future<?> f3 = executor.submit(() -> {
                slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz");
                thirdJobFinished.countDown();
            });

            for (int i = 0; i < 50 && slowValidator.getAdmissionController().getQueuedCount() == 0; i++) {
                Thread.sleep(10);
            }
            assertThat(slowValidator.getAdmissionController().getQueuedCount()).isEqualTo(1);

            // Release the first two workers
            releaseLatch.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);

            // 3rd job must now acquire and complete
            assertThat(thirdJobFinished.await(5, TimeUnit.SECONDS)).isTrue();
            f3.get(5, TimeUnit.SECONDS);

            assertThat(slowValidator.getAdmissionController().getActiveCount()).isEqualTo(0);
            assertThat(slowValidator.getAdmissionController().getQueuedCount()).isEqualTo(0);
        } finally {
            releaseLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validationQueueSaturationRejectionWhenQueueIsFull() throws Exception {
        properties.setMaxConcurrentValidations(1);
        properties.setMaxQueuedValidations(1);
        properties.setValidationQueueWaitTimeout(Duration.ofSeconds(5));

        CountDownLatch inValidation = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        SlowInspectValidator slowValidator = new SlowInspectValidator(
                properties, malwareScanner, inValidation, releaseLatch);

        Path archive = createValidKafkaArchive();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            executor.submit(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"));
            assertThat(inValidation.await(5, TimeUnit.SECONDS)).isTrue();

            // 2nd request fills the queue (maxQueued = 1)
            executor.submit(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"));
            for (int i = 0; i < 50 && slowValidator.getAdmissionController().getQueuedCount() == 0; i++) {
                Thread.sleep(10);
            }
            assertThat(slowValidator.getAdmissionController().getQueuedCount()).isEqualTo(1);

            // 3rd request arrives with active=1 and queued=1 -> immediately rejected with queue full
            assertThatThrownBy(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"))
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("validation queue is full");
        } finally {
            releaseLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validationQueueWaitTimeoutRejection() throws Exception {
        properties.setMaxConcurrentValidations(1);
        properties.setMaxQueuedValidations(2);
        properties.setValidationQueueWaitTimeout(Duration.ofMillis(80));

        CountDownLatch inValidation = new CountDownLatch(1);
        CountDownLatch releaseLatch = new CountDownLatch(1);

        SlowInspectValidator slowValidator = new SlowInspectValidator(
                properties, malwareScanner, inValidation, releaseLatch);

        Path archive = createValidKafkaArchive();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"));
            assertThat(inValidation.await(5, TimeUnit.SECONDS)).isTrue();

            // 2nd request waits and times out after 80ms
            assertThatThrownBy(() -> slowValidator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"))
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("validation queue wait timeout exceeded");
        } finally {
            releaseLatch.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validationSlotReleasedBeforeMalwareScanBegins() throws Exception {
        properties.setMaxConcurrentValidations(1);
        properties.setMaxQueuedValidations(2);
        validator = new PackageValidator(properties, malwareScanner);

        Path archive1 = createValidKafkaArchive();
        Path archive2 = createValidKafkaArchive();

        CountDownLatch scanStarted = new CountDownLatch(1);
        CountDownLatch scanProceed = new CountDownLatch(1);

        doAnswer(inv -> {
            scanStarted.countDown();
            scanProceed.await(5, TimeUnit.SECONDS);
            return null;
        }).when(malwareScanner).scan(any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // Request 1 validates archive 1 and blocks in malwareScanner.scan
            Future<?> f1 = executor.submit(() ->
                    validator.validate(archive1, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"));

            assertThat(scanStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Crucial assertion: validation admission slot must be released while malware scan is in progress!
            assertThat(validator.getAdmissionController().getActiveCount()).isEqualTo(0);

            // Request 2 must be able to acquire validation capacity immediately without waiting
            CountDownLatch request2Validated = new CountDownLatch(1);
            Future<?> f2 = executor.submit(() -> {
                try (var lease = validator.getAdmissionController().acquire()) {
                    request2Validated.countDown();
                }
            });

            assertThat(request2Validated.await(2, TimeUnit.SECONDS)).isTrue();
            f2.get(2, TimeUnit.SECONDS);

            scanProceed.countDown();
            f1.get(5, TimeUnit.SECONDS);
        } finally {
            scanProceed.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void validatorShutdownClosesAdmissionController() {
        validator.destroy();
        assertThat(validator.getAdmissionController().isShutdown()).isTrue();
    }

    static class SlowInspectValidator extends PackageValidator {
        private final CountDownLatch inInspection;
        private final CountDownLatch proceedLatch;

        SlowInspectValidator(StorageProperties properties, MalwareScanner malwareScanner,
                             CountDownLatch inInspection, CountDownLatch proceedLatch) {
            super(properties, malwareScanner);
            this.inInspection = inInspection;
            this.proceedLatch = proceedLatch;
        }

        @Override
        protected void inspectArchive(Path archivePath, ServiceType serviceType, String expectedVersion, String fileName) {
            inInspection.countDown();
            try {
                proceedLatch.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.inspectArchive(archivePath, serviceType, expectedVersion, fileName);
        }
    }

    @Test
    void permitsReleasedAfterPackageValidationException() throws IOException {
        Path invalidExt = Files.writeString(tempDir.resolve("bad.zip"), "invalid");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> validator.validate(invalidExt, ServiceType.KAFKA, "3.7.0", "bad.zip"))
                    .isInstanceOf(PackageValidationException.class)
                    .hasMessageContaining("Invalid package extension");
        }
    }

    @Test
    void permitsReleasedAfterCorruptedArchiveValidationException() throws IOException {
        Path corruptTgz = Files.writeString(tempDir.resolve("corrupt.tgz"), "not-a-tar-gz");

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> validator.validate(corruptTgz, ServiceType.KAFKA, "3.7.0", "corrupt.tgz"))
                    .isInstanceOf(PackageValidationException.class);
        }
    }

    @Test
    void permitsReleasedAfterMalwareScanException() throws Exception {
        Path archive = createValidKafkaArchive();
        doThrow(new PackageValidationException("Malware detected"))
                .when(malwareScanner).scan(any());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> validator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"))
                    .isInstanceOf(PackageValidationException.class)
                    .hasMessageContaining("Malware detected");
        }
    }

    @Test
    void permitsReleasedAfterScannerSaturationException() throws Exception {
        Path archive = createValidKafkaArchive();
        doThrow(new UploadLimitExceededException("Malware scanner capacity is full"))
                .when(malwareScanner).scan(any());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> validator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"))
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("Malware scanner capacity is full");
        }
    }

    @Test
    void permitsReleasedAfterUnexpectedRuntimeException() throws Exception {
        Path archive = createValidKafkaArchive();
        doThrow(new RuntimeException("Unexpected error"))
                .when(malwareScanner).scan(any());

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> validator.validate(archive, ServiceType.KAFKA, "3.7.0", "kafka_2.13-3.7.0.tgz"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Unexpected error");
        }
    }

    private Path createValidKafkaArchive() throws IOException {
        byte[] jarContents = "kafka-jar-content".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzi = new GzipCompressorOutputStream(baos);
             TarArchiveOutputStream tai = new TarArchiveOutputStream(gzi)) {
            TarArchiveEntry entry = new TarArchiveEntry("kafka_2.13-3.7.0/libs/kafka_2.13-3.7.0.jar");
            entry.setSize(jarContents.length);
            tai.putArchiveEntry(entry);
            tai.write(jarContents);
            tai.closeArchiveEntry();
            tai.finish();
        }
        return Files.write(tempDir.resolve("kafka_2.13-3.7.0.tgz"), baos.toByteArray());
    }
}
