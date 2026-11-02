package io.translab.tantor.artifact.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ArtifactResponse;
import io.translab.tantor.artifact.exception.OperationTimeoutException;
import io.translab.tantor.artifact.exception.StorageException;
import io.translab.tantor.artifact.util.OperationDeadline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadIdempotencyServiceTest {

    private StorageProperties properties;
    private ObjectMapper objectMapper;
    private UploadIdempotencyService service;

    @TempDir
    Path repoDir;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setBasePath(repoDir.toString());
        properties.setUploadTimeout(Duration.ofSeconds(5));

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        service = new UploadIdempotencyService(properties, objectMapper);
        service.init();
    }

    private Artifact createArtifact(String key, String sha256) {
        Artifact a = new Artifact();
        a.setId(UUID.randomUUID());
        a.setRootArtifactId(a.getId());
        a.setServiceType(ServiceType.KAFKA);
        a.setVersion("3.7.0");
        a.setFileName("kafka_2.13-3.7.0.tgz");
        a.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        a.setChecksumSha256(sha256);
        a.setStatus(ArtifactStatus.AVAILABLE);
        a.setFileSizeBytes(1024L);
        a.setCreatedBy("system");
        return a;
    }

    @Test
    void claimOrAwaitReturnsNullForFirstRequest() {
        ArtifactResponse res = service.claimOrAwait("key-1", ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(res).isNull();
    }

    @Test
    void completedRequestReturnsCachedResponse() {
        String key = "key-success";
        Artifact a = createArtifact(key, "sha256-abc");
        ArtifactResponse responseDto = ArtifactResponse.from(a);

        // 1. Initial claim
        assertThat(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited())).isNull();

        // 2. Mark success
        service.recordSuccess(key, a, responseDto);

        // 3. Subsequent identical request
        ArtifactResponse cached = service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(cached).isNotNull();
        assertThat(cached.id()).isEqualTo(a.getId());
        assertThat(cached.sha256()).isEqualTo("sha256-abc");
    }

    @Test
    void reusingKeyWithDifferentParametersThrows() {
        String key = "key-conflict";
        Artifact a = createArtifact(key, "sha256-abc");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        // Different service type
        assertThatThrownBy(() -> service.claimOrAwait(key, ServiceType.SCHEMA_REGISTRY, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was already used for a different artifact");

        // Different version
        assertThatThrownBy(() -> service.claimOrAwait(key, ServiceType.KAFKA, "3.8.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRequestsWithSameKeyAwaitWinner() throws Exception {
        String key = "concurrent-key";
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch thread1Claimed = new CountDownLatch(1);
        CountDownLatch thread2Awaiting = new CountDownLatch(1);

        AtomicReference<ArtifactResponse> resp1 = new AtomicReference<>();
        AtomicReference<ArtifactResponse> resp2 = new AtomicReference<>();

        Artifact artifact = createArtifact(key, "sha256-shared");
        ArtifactResponse finalResponse = ArtifactResponse.from(artifact);

        // Thread 1 claims
        Future<?> f1 = executor.submit(() -> {
            resp1.set(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                    "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited()));
            thread1Claimed.countDown();
            try {
                thread2Awaiting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            service.recordSuccess(key, artifact, finalResponse);
        });

        // Thread 2 waits
        Future<?> f2 = executor.submit(() -> {
            try {
                thread1Claimed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            thread2Awaiting.countDown();
            resp2.set(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                    "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited()));
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(resp1.get()).isNull(); // Thread 1 was the winner that claimed to execute
        assertThat(resp2.get()).isNotNull(); // Thread 2 received the completed response
        assertThat(resp2.get().id()).isEqualTo(artifact.getId());
    }

    @Test
    void failedUploadClearsInFlightAllowingRetry() {
        String key = "retry-key";

        assertThat(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited())).isNull();

        // Simulate failure
        service.recordFailure(key, new RuntimeException("Validation failed"));

        // Retry should be able to claim again
        assertThat(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited())).isNull();
    }

    @Test
    void persistedRecordsSurviveServiceRestart() {
        String key = "persistent-key";
        Artifact a = createArtifact(key, "sha256-persist");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        // Create new service instance pointing to same storage
        UploadIdempotencyService newService = new UploadIdempotencyService(properties, objectMapper);
        newService.init();

        ArtifactResponse loaded = newService.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(loaded).isNotNull();
        assertThat(loaded.id()).isEqualTo(a.getId());
        assertThat(loaded.sha256()).isEqualTo("sha256-persist");
    }

    @Test
    void sameKeyAndSameContentReturnsCachedResponse() {
        String key = "key-same-content";
        Artifact a = createArtifact(key, "sha256-expected");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        ArtifactResponse resp = service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", "sha256-expected", OperationDeadline.unlimited());
        assertThat(resp).isNotNull();
        assertThat(resp.sha256()).isEqualTo("sha256-expected");
    }

    @Test
    void sameKeyAndDifferentContentThrowsException() {
        String key = "key-diff-content";
        Artifact a = createArtifact(key, "sha256-first-upload");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        assertThatThrownBy(() -> service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", "sha256-tampered-content", OperationDeadline.unlimited()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was already used for different content");
    }

    @Test
    void cachedRequestWithDifferentActualFingerprintThrows() {
        String key = "key-diff-actual";
        Artifact a = createArtifact(key, "sha256-first-upload");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        assertThatThrownBy(() -> service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", null, () -> "sha256-different-actual", OperationDeadline.unlimited()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was already used for different content");
    }

    @Test
    void cachedRequestWithMatchingActualFingerprintSucceeds() {
        String key = "key-match-actual";
        Artifact a = createArtifact(key, "sha256-match");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        ArtifactResponse resp = service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", null, () -> "sha256-match", OperationDeadline.unlimited());
        assertThat(resp).isNotNull();
        assertThat(resp.sha256()).isEqualTo("sha256-match");
    }

    @Test
    void concurrentRequestsWithDifferentActualContentThrows() throws Exception {
        String key = "concurrent-diff-actual";
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch thread1Claimed = new CountDownLatch(1);
        CountDownLatch thread2Awaiting = new CountDownLatch(1);

        AtomicReference<Throwable> thread2Error = new AtomicReference<>();

        Artifact artifact = createArtifact(key, "sha256-first-stream");
        ArtifactResponse finalResponse = ArtifactResponse.from(artifact);

        // Thread 1 claims without declared checksum
        Future<?> f1 = executor.submit(() -> {
            service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                    "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
            thread1Claimed.countDown();
            try {
                thread2Awaiting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            service.recordSuccess(key, artifact, finalResponse);
        });

        // Thread 2 waits concurrently without declared checksum, but with different
        // actual stream fingerprint
        Future<?> f2 = executor.submit(() -> {
            try {
                thread1Claimed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            thread2Awaiting.countDown();
            try {
                service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                        "kafka_2.13-3.7.0.tgz", null, () -> "sha256-second-stream-different",
                        OperationDeadline.unlimited());
            } catch (Throwable t) {
                thread2Error.set(t);
            }
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(thread2Error.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was already used for different content");
    }

    @Test
    void concurrentRequestsWithSameActualContentSucceeds() throws Exception {
        String key = "concurrent-same-actual";
        ExecutorService executor = Executors.newFixedThreadPool(2);

        CountDownLatch thread1Claimed = new CountDownLatch(1);
        CountDownLatch thread2Awaiting = new CountDownLatch(1);

        AtomicReference<ArtifactResponse> resp2 = new AtomicReference<>();

        Artifact artifact = createArtifact(key, "sha256-identical-stream");
        ArtifactResponse finalResponse = ArtifactResponse.from(artifact);

        // Thread 1 claims
        Future<?> f1 = executor.submit(() -> {
            service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                    "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
            thread1Claimed.countDown();
            try {
                thread2Awaiting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            service.recordSuccess(key, artifact, finalResponse);
        });

        // Thread 2 waits concurrently with matching actual fingerprint
        Future<?> f2 = executor.submit(() -> {
            try {
                thread1Claimed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            thread2Awaiting.countDown();
            resp2.set(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                    "kafka_2.13-3.7.0.tgz", null, () -> "sha256-identical-stream", OperationDeadline.unlimited()));
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(resp2.get()).isNotNull();
        assertThat(resp2.get().sha256()).isEqualTo("sha256-identical-stream");
    }

    @Test
    void expiredIdempotencyRecordIsPurged() throws Exception {
        String key = "key-expired";
        Artifact a = createArtifact(key, "sha256-exp");
        service.recordSuccess(key, a, ArtifactResponse.from(a));

        assertThat(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited())).isNotNull();

        // Simulate passage of time and purge with zero retention (everything before now
        // is purged)
        Thread.sleep(10);
        int purged = service.purgeExpiredRecords(Duration.ZERO);
        assertThat(purged).isGreaterThanOrEqualTo(0);
    }

    @Test
    void recordSuccessThrowsStorageExceptionWhenPersistenceFailsAndDoesNotCache() throws Exception {
        String key = "key-persist-fail";
        Artifact a = createArtifact(key, "sha256-persist-fail");
        ArtifactResponse responseDto = ArtifactResponse.from(a);

        // Claim key first
        assertThat(service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited())).isNull();

        // Point basePath to a regular file so createDirectories fails
        java.nio.file.Path blockedFile = repoDir.resolve("blocked-file-1");
        java.nio.file.Files.writeString(blockedFile, "blocker");
        properties.setBasePath(blockedFile.toString());

        assertThatThrownBy(() -> service.recordSuccess(key, a, responseDto))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Failed to persist idempotency record");

        // Restore basePath
        properties.setBasePath(repoDir.toString());

        // A retry should be able to claim the key again because it was never completed
        ArtifactResponse retryClaim = service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(retryClaim).isNull();
    }

    @Test
    void concurrentWaitersFailWhenPersistenceFails() throws Exception {
        String key = "key-concurrent-persist-fail";
        Artifact a = createArtifact(key, "sha256-persist-fail2");
        ArtifactResponse responseDto = ArtifactResponse.from(a);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch thread1Claimed = new CountDownLatch(1);
        CountDownLatch thread2Awaiting = new CountDownLatch(1);

        AtomicReference<Throwable> thread1Error = new AtomicReference<>();
        AtomicReference<Throwable> thread2Error = new AtomicReference<>();

        Future<?> f1 = executor.submit(() -> {
            service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                    "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
            thread1Claimed.countDown();
            try {
                thread2Awaiting.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }

            try {
                // Point basePath to a regular file so persistence fails
                java.nio.file.Path blockedFile = repoDir.resolve("blocked-file-2");
                java.nio.file.Files.writeString(blockedFile, "blocker");
                properties.setBasePath(blockedFile.toString());
                service.recordSuccess(key, a, responseDto);
            } catch (Throwable t) {
                thread1Error.set(t);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            try {
                thread1Claimed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
            }
            thread2Awaiting.countDown();
            try {
                service.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                        "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
            } catch (Throwable t) {
                thread2Error.set(t);
            }
        });

        f1.get(5, TimeUnit.SECONDS);
        f2.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Restore basePath
        properties.setBasePath(repoDir.toString());

        // Both thread 1 and thread 2 must receive failures, not silent success
        assertThat(thread1Error.get()).isInstanceOf(StorageException.class);
        assertThat(thread2Error.get()).isInstanceOf(StorageException.class);
    }

    @Test
    void expiredDeadlineWhileWaitingThrowsOperationTimeoutException() {
        String key = "expired-wait-deadline";

        // First request claims the idempotency key.
        assertThat(service.claimOrAwait(
                key,
                ServiceType.KAFKA,
                "3.7.0",
                "kafka_2.13-3.7.0.tgz",
                OperationDeadline.unlimited())).isNull();

        // Second request has no remaining time.
        OperationDeadline expiredDeadline = OperationDeadline.start(Duration.ZERO);

        assertThatThrownBy(() -> service.claimOrAwait(
                key,
                ServiceType.KAFKA,
                "3.7.0",
                "kafka_2.13-3.7.0.tgz",
                expiredDeadline))
                .isInstanceOf(OperationTimeoutException.class)
                .hasMessageContaining("Timeout awaiting concurrent upload");
    }

    @Test
    void waitingRequestTimeoutThrowsOperationTimeoutException() {
        String key = "waiting-request-timeout";

        // First request claims the key and remains in progress.
        assertThat(service.claimOrAwait(
                key,
                ServiceType.KAFKA,
                "3.7.0",
                "kafka_2.13-3.7.0.tgz",
                OperationDeadline.unlimited())).isNull();

        // Second request waits with a short deadline.
        OperationDeadline deadline = OperationDeadline.start(Duration.ofMillis(100));

        assertThatThrownBy(() -> service.claimOrAwait(
                key,
                ServiceType.KAFKA,
                "3.7.0",
                "kafka_2.13-3.7.0.tgz",
                deadline))
                .isInstanceOf(OperationTimeoutException.class)
                .hasMessageContaining("Timeout awaiting concurrent upload");
    }

}
