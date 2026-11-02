package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ChecksumResult;
import io.translab.tantor.artifact.dto.ManifestDto;
import io.translab.tantor.artifact.exception.ChecksumMismatchException;
import io.translab.tantor.artifact.exception.ArtifactAlreadyExistsException;
import io.translab.tantor.artifact.exception.PackageValidationException;
import io.translab.tantor.artifact.exception.StorageException;
import io.translab.tantor.artifact.exception.OperationTimeoutException;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.translab.tantor.artifact.util.OperationDeadline;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArtifactServiceTest {

    @Mock ArtifactJpaRepository repository;
    @Mock StorageService storageService;
    @Mock ManifestService manifestService;

    @Mock PackageValidator packageValidator;

    StorageProperties properties;
    ArtifactCommitHelper commitHelper;
    ArtifactService service;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.setEnforceChecksum(true);
        commitHelper = new ArtifactCommitHelper(repository, storageService, manifestService);
        service = new ArtifactService(repository,
                storageService, manifestService, properties, packageValidator, commitHelper);

        lenient().when(storageService.relativeDir(any(), anyString(), any()))
                .thenReturn("kafka/3.7.0");
        lenient().when(manifestService.build(any(), any()))
                .thenReturn(new ManifestDto(1, ServiceType.KAFKA, "kafka", "3.7.0", null,
                        "kafka_2.13-3.7.0.tgz", 100L, "sha", "md5", "application/gzip", null, Map.of()));
        lenient().when(manifestService.toJson(any())).thenReturn("{}");
        lenient().when(repository.save(any(Artifact.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageService.resolveBinary(anyString(), anyString())).thenReturn(Path.of("repository", "artifact.tgz"));
    }

    @Test
    void artifactServiceHasSingleConstructorRequiringCommitHelper() {
        assertThat(ArtifactService.class.getConstructors()).hasSize(1);
        assertThatThrownBy(() -> new ArtifactService(repository, storageService, manifestService, properties, packageValidator, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("commitHelper must not be null");
    }

    @Test
    void uploadStoresAndMarksAvailable() {
        when(storageService.storeTemporarily(eq("kafka_2.13-3.7.0.tgz"), any(InputStream.class)))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        Artifact result = service.upload(cmd(null, false), data());

        assertThat(result.getStatus()).isEqualTo(ArtifactStatus.AVAILABLE);
        assertThat(result.getChecksumSha256()).isEqualTo("abc123");
        assertThat(result.getRelativePath()).startsWith("kafka/3.7.0/")
                .endsWith("/kafka_2.13-3.7.0.tgz");
        verify(storageService).writeManifest(eq("kafka/3.7.0/" + result.getId()), anyString());
    }

    @Test
    void repeatedUploadIsRejected() {
        when(storageService.storeTemporarily(eq("kafka_2.13-3.7.0.tgz"), any(InputStream.class)))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        when(repository.countActiveByServiceTypeAndVersion(ServiceType.KAFKA, "3.7.0"))
                .thenReturn(1L);

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(ArtifactAlreadyExistsException.class);
        verify(repository, never()).save(any(Artifact.class));
    }

    @Test
    void uploadFailsAndCleansUpOnChecksumMismatch() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("computed-real", "md5", 100L)));

        assertThatThrownBy(() -> service.upload(cmd("declared-wrong", false), data()))
                .isInstanceOf(ChecksumMismatchException.class);

        verify(storageService, times(1)).deleteTemp(any());
        verify(repository, never()).save(any());
    }

    @Test
    void unsafeFilenameIsRejectedBeforeAnyUploadIsStored() {
        ArtifactService.UploadCommand unsafe = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "../malicious.tgz",
                "application/gzip", "test", null, Map.of(), false, "tester");

        assertThatThrownBy(() -> service.upload(unsafe, data()))
                .isInstanceOf(IllegalArgumentException.class);
        verify(storageService, never()).storeTemporarily(anyString(), any());
    }

    @Test
    void capacityRejectionCleansUpTempWithoutQuarantineOrSave() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        org.mockito.Mockito.doThrow(new UploadLimitExceededException("Validation capacity is full"))
                .when(packageValidator).validate(any(), any(), any(), any());

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("Validation capacity is full");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, never()).quarantine(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void operationTimeoutDuringValidationPropagatesWithoutQuarantineOrFailedRecord() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        org.mockito.Mockito.doThrow(new OperationTimeoutException("Operation timeout exceeded during package validation"))
                .when(packageValidator).validate(any(), any(), any(), any());

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(OperationTimeoutException.class)
                .hasMessageContaining("Operation timeout exceeded during package validation");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, never()).quarantine(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void operationTimeoutWithDeadlinePropagatesWithoutQuarantineOrFailedRecord() {
        OperationDeadline deadline = OperationDeadline.start(java.time.Duration.ofMillis(100));
        when(storageService.storeTemporarily(eq("kafka_2.13-3.7.0.tgz"), any(), eq(deadline), eq(-1L)))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        org.mockito.Mockito.doThrow(new OperationTimeoutException("Operation timeout exceeded during archive inspection"))
                .when(packageValidator).validate(any(), any(), any(), any(), eq(deadline));

        ArtifactService.UploadCommand cmd = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null,
                "kafka_2.13-3.7.0.tgz", "application/gzip", null, null, Map.of(), false, "user", null, deadline);

        assertThatThrownBy(() -> service.upload(cmd, data()))
                .isInstanceOf(OperationTimeoutException.class)
                .hasMessageContaining("Operation timeout exceeded during archive inspection");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, never()).quarantine(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void validationFailureQuarantinesAndPersistsFailedArtifact() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        org.mockito.Mockito.doThrow(new PackageValidationException("Corrupt tarball"))
                .when(packageValidator).validate(any(), any(), any(), any());

        Artifact result = service.upload(cmd(null, false), data());

        assertThat(result.getStatus()).isEqualTo(ArtifactStatus.FAILED);
        verify(storageService, times(1)).quarantine(any(), eq("kafka_2.13-3.7.0.tgz"));
        verify(storageService, never()).moveToFinal(any(), anyString(), anyString());
        verify(repository, times(1)).save(any(Artifact.class));
    }

    @Test
    void unexpectedValidationExceptionDoesNotQuarantineOrRecordFailed() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        org.mockito.Mockito.doThrow(new IllegalStateException("Unexpected infrastructure error"))
                .when(packageValidator).validate(any(), any(), any(), any());

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unexpected infrastructure error");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, never()).quarantine(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void advisoryLocksAcquiredInDeterministicOrderFollowedByAuthoritativeDuplicateChecks() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        service.upload(cmd(null, false), data());

        InOrder inOrder = org.mockito.Mockito.inOrder(repository);
        // Lock checksum first, then version
        inOrder.verify(repository).acquireUploadLock("artifact-checksum:abc123");
        inOrder.verify(repository).acquireUploadLock("artifact-version:KAFKA:3.7.0");
        // Authoritative duplicate checks run strictly after acquiring advisory locks
        inOrder.verify(repository).countActiveByServiceTypeAndVersion(ServiceType.KAFKA, "3.7.0");
        inOrder.verify(repository).countActiveByChecksumSha256("abc123");
    }

    @Test
    void duplicateDetectedUnderLockThrowsAndCleansUp() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        // Pass preliminary check (0), but simulate duplicate detected inside lock (1)
        when(repository.countActiveByServiceTypeAndVersion(ServiceType.KAFKA, "3.7.0"))
                .thenReturn(0L, 1L);

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(ArtifactAlreadyExistsException.class)
                .hasMessageContaining("Artifact already exists");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, never()).moveToFinal(any(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void duplicateChecksumDetectedUnderLockThrowsAndCleansUp() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        when(repository.countActiveByChecksumSha256("abc123"))
                .thenReturn(0L, 1L);

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(ArtifactAlreadyExistsException.class)
                .hasMessageContaining("An artifact with the same SHA-256 checksum already exists");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, never()).moveToFinal(any(), anyString(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void databaseSaveFailureCleansUpMovedBinaryAndManifest() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        when(repository.save(any(Artifact.class))).thenThrow(new RuntimeException("Database constraint failure"));

        assertThatThrownBy(() -> service.upload(cmd(null, false), data()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Database constraint failure");

        verify(storageService, times(1)).moveToFinal(any(), anyString(), eq("kafka_2.13-3.7.0.tgz"));
        verify(storageService, times(1)).deleteBinary(anyString(), eq("kafka_2.13-3.7.0.tgz"));
    }

    @Test
    void transactionCommitFailureCleansUpMovedBinaryAndManifest() {
        ArtifactCommitHelper mockHelper = org.mockito.Mockito.mock(ArtifactCommitHelper.class);
        ArtifactService serviceWithMock = new ArtifactService(
                repository, storageService, manifestService, properties, packageValidator, mockHelper);

        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        // Simulate Spring's TransactionSystemException thrown during transaction commit after method returns
        when(mockHelper.commitAvailableArtifact(any(), any(), any(), any(), any(), any()))
                .thenThrow(new org.springframework.transaction.TransactionSystemException("Could not commit JPA transaction"));

        assertThatThrownBy(() -> serviceWithMock.upload(cmd(null, false), data()))
                .isInstanceOf(org.springframework.transaction.TransactionSystemException.class)
                .hasMessageContaining("Could not commit JPA transaction");

        verify(storageService, times(1)).deleteTemp(any());
        verify(storageService, times(1)).deleteBinary(anyString(), eq("kafka_2.13-3.7.0.tgz"));
    }

    @Test
    void overwriteWithIdenticalChecksumReturnsExistingArtifact() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("abc123", "md5val", 100L)));

        Artifact existing = new Artifact();
        existing.setId(UUID.randomUUID());
        existing.setServiceType(ServiceType.KAFKA);
        existing.setVersion("3.7.0");
        existing.setChecksumSha256("abc123");
        existing.setStatus(ArtifactStatus.AVAILABLE);

        when(repository.findActiveByServiceTypeAndVersion(ServiceType.KAFKA, "3.7.0"))
                .thenReturn(List.of(existing));

        Artifact result = service.upload(cmd(null, true), data());

        assertThat(result.getId()).isEqualTo(existing.getId());
        verify(storageService, never()).moveToFinal(any(), anyString(), anyString());
    }

    @Test
    void overwriteWithDifferentChecksumSoftDeletesOldVersion() {
        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult("new-sha256", "md5val", 200L)));

        Artifact existing = new Artifact();
        existing.setId(UUID.randomUUID());
        existing.setServiceType(ServiceType.KAFKA);
        existing.setVersion("3.7.0");
        existing.setFileName("kafka_2.13-3.7.0.tgz");
        existing.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        existing.setChecksumSha256("old-sha256");
        existing.setStatus(ArtifactStatus.AVAILABLE);

        when(repository.findActiveByServiceTypeAndVersion(ServiceType.KAFKA, "3.7.0"))
                .thenReturn(List.of(existing));
        when(repository.save(any(Artifact.class))).thenAnswer(inv -> inv.getArgument(0));

        Artifact result = service.upload(cmd(null, true), data());

        assertThat(result.getChecksumSha256()).isEqualTo("new-sha256");
        // Verify soft-delete of old version and deletion of old binary
        verify(storageService, times(1)).deleteBinary("kafka/3.7.0", "kafka_2.13-3.7.0.tgz");
        verify(storageService, times(1)).moveToFinal(any(), anyString(), eq("kafka_2.13-3.7.0.tgz"));
    }

    @Test
    void deleteWithoutActiveTransactionDeletesBinaryImmediately() {
        UUID id = UUID.randomUUID();
        Artifact existing = new Artifact();
        existing.setId(id);
        existing.setFileName("kafka.tgz");
        existing.setRelativePath("kafka/3.7.0/kafka.tgz");
        existing.setStatus(ArtifactStatus.AVAILABLE);

        when(repository.findById(id)).thenReturn(Optional.of(existing));

        service.delete(id);

        verify(repository, times(1)).save(any(Artifact.class));
        verify(storageService, times(1)).deleteBinary("kafka/3.7.0", "kafka.tgz");
    }

    @Test
    void concurrentUploadWithSameIdempotencyKeyAndDifferentContentThrows() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Path tempDir = java.nio.file.Files.createTempDirectory("idemp-test");
        properties.setBasePath(tempDir.toString());
        UploadIdempotencyService idempService = new UploadIdempotencyService(properties, mapper);
        idempService.init();

        ArtifactCommitHelper txCommitHelper = createTransactionalProxy(commitHelper);
        ArtifactService txService = new ArtifactService(repository,
                storageService, manifestService, properties, packageValidator, txCommitHelper);
        txService.setIdempotencyService(idempService);

        byte[] payload1 = "stream-content-alpha".getBytes();
        byte[] payload2 = "stream-content-beta".getBytes();
        String sha1 = new ChecksumService().digest(new ByteArrayInputStream(payload1)).sha256();

        java.util.concurrent.CountDownLatch request1Started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch request2Waiting = new java.util.concurrent.CountDownLatch(1);

        when(storageService.storeTemporarily(any(), any())).thenAnswer(inv -> {
            request1Started.countDown();
            try {
                request2Waiting.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            return new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult(sha1, "md5", (long) payload1.length));
        });

        Artifact savedArtifact = new Artifact();
        savedArtifact.setId(UUID.randomUUID());
        savedArtifact.setServiceType(ServiceType.KAFKA);
        savedArtifact.setVersion("3.7.0");
        savedArtifact.setFileName("kafka_2.13-3.7.0.tgz");
        savedArtifact.setChecksumSha256(sha1);
        savedArtifact.setStatus(ArtifactStatus.AVAILABLE);
        when(repository.save(any(Artifact.class))).thenReturn(savedArtifact);
        lenient().when(repository.findById(savedArtifact.getId())).thenReturn(Optional.of(savedArtifact));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicReference<Throwable> request2Error = new java.util.concurrent.atomic.AtomicReference<>();

        ArtifactService.UploadCommand cmd1 = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user1", "idemp-key-concurrent", null);
        ArtifactService.UploadCommand cmd2 = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user2", "idemp-key-concurrent", null);

        java.util.concurrent.Future<?> f1 = executor.submit(() -> {
            txService.upload(cmd1, new ByteArrayInputStream(payload1));
        });

        java.util.concurrent.Future<?> f2 = executor.submit(() -> {
            try {
                request1Started.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            request2Waiting.countDown();
            try {
                txService.upload(cmd2, new ByteArrayInputStream(payload2));
            } catch (Throwable t) {
                request2Error.set(t);
            }
        });

        f1.get(5, java.util.concurrent.TimeUnit.SECONDS);
        f2.get(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(request2Error.get())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was already used for different content");
    }

    @Test
    void concurrentUploadWithSameIdempotencyKeyAndIdenticalContentSucceeds() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Path tempDir = java.nio.file.Files.createTempDirectory("idemp-test2");
        properties.setBasePath(tempDir.toString());
        UploadIdempotencyService idempService = new UploadIdempotencyService(properties, mapper);
        idempService.init();

        ArtifactCommitHelper txCommitHelper = createTransactionalProxy(commitHelper);
        ArtifactService txService = new ArtifactService(repository,
                storageService, manifestService, properties, packageValidator, txCommitHelper);
        txService.setIdempotencyService(idempService);

        byte[] payload = "stream-content-identical".getBytes();
        String sha = new ChecksumService().digest(new ByteArrayInputStream(payload)).sha256();

        java.util.concurrent.CountDownLatch request1Started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch request2Waiting = new java.util.concurrent.CountDownLatch(1);

        when(storageService.storeTemporarily(any(), any())).thenAnswer(inv -> {
            request1Started.countDown();
            try {
                request2Waiting.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            return new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult(sha, "md5", (long) payload.length));
        });

        Artifact savedArtifact = new Artifact();
        savedArtifact.setId(UUID.randomUUID());
        savedArtifact.setServiceType(ServiceType.KAFKA);
        savedArtifact.setVersion("3.7.0");
        savedArtifact.setFileName("kafka_2.13-3.7.0.tgz");
        savedArtifact.setChecksumSha256(sha);
        savedArtifact.setStatus(ArtifactStatus.AVAILABLE);
        when(repository.save(any(Artifact.class))).thenReturn(savedArtifact);
        when(repository.findById(savedArtifact.getId())).thenReturn(Optional.of(savedArtifact));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicReference<Artifact> resp1 = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Artifact> resp2 = new java.util.concurrent.atomic.AtomicReference<>();

        ArtifactService.UploadCommand cmd1 = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user1", "idemp-key-identical", null);
        ArtifactService.UploadCommand cmd2 = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user2", "idemp-key-identical", null);

        java.util.concurrent.Future<?> f1 = executor.submit(() -> {
            resp1.set(txService.upload(cmd1, new ByteArrayInputStream(payload)));
        });

        java.util.concurrent.Future<?> f2 = executor.submit(() -> {
            try {
                request1Started.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            request2Waiting.countDown();
            resp2.set(txService.upload(cmd2, new ByteArrayInputStream(payload)));
        });

        f1.get(5, java.util.concurrent.TimeUnit.SECONDS);
        f2.get(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(resp1.get()).isNotNull();
        assertThat(resp2.get()).isNotNull();
        assertThat(resp2.get().getId()).isEqualTo(resp1.get().getId());
    }


    @Test
    void successfulCommitTriggersIdempotencySuccessRecording() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Path tempDir = java.nio.file.Files.createTempDirectory("idemp-success-test");
        properties.setBasePath(tempDir.toString());
        UploadIdempotencyService idempService = new UploadIdempotencyService(properties, mapper);
        idempService.init();

        ArtifactCommitHelper txCommitHelper = createTransactionalProxy(commitHelper);
        ArtifactService txService = new ArtifactService(repository,
                storageService, manifestService, properties, packageValidator, txCommitHelper);
        txService.setIdempotencyService(idempService);

        byte[] payload = "stream-content-success".getBytes();
        String sha = new ChecksumService().digest(new ByteArrayInputStream(payload)).sha256();

        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult(sha, "md5", (long) payload.length)));

        Artifact savedArtifact = new Artifact();
        savedArtifact.setId(UUID.randomUUID());
        savedArtifact.setServiceType(ServiceType.KAFKA);
        savedArtifact.setVersion("3.7.0");
        savedArtifact.setFileName("kafka_2.13-3.7.0.tgz");
        savedArtifact.setChecksumSha256(sha);
        savedArtifact.setStatus(ArtifactStatus.AVAILABLE);
        when(repository.save(any(Artifact.class))).thenReturn(savedArtifact);
        when(repository.findById(savedArtifact.getId())).thenReturn(Optional.of(savedArtifact));

        String key = "idemp-key-success-commit";
        ArtifactService.UploadCommand cmd = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user1", key, null);

        Artifact result = txService.upload(cmd, new ByteArrayInputStream(payload));
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(savedArtifact.getId());

        io.translab.tantor.artifact.dto.ArtifactResponse cached = idempService.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(cached).isNotNull();
        assertThat(cached.id()).isEqualTo(savedArtifact.getId());
        assertThat(cached.sha256()).isEqualTo(sha);

        // Verify that retry upload with same key returns the existing committed artifact
        Artifact retryResult = txService.upload(cmd, new ByteArrayInputStream(payload));
        assertThat(retryResult).isNotNull();
        assertThat(retryResult.getId()).isEqualTo(savedArtifact.getId());
    }

    @Test
    void transactionRollbackDoesNotTriggerSuccessRecording() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Path tempDir = java.nio.file.Files.createTempDirectory("idemp-rollback-test");
        properties.setBasePath(tempDir.toString());
        UploadIdempotencyService idempService = new UploadIdempotencyService(properties, mapper);
        idempService.init();

        ArtifactCommitHelper txCommitHelper = createTransactionalProxy(commitHelper);
        ArtifactService txService = new ArtifactService(repository,
                storageService, manifestService, properties, packageValidator, txCommitHelper);
        txService.setIdempotencyService(idempService);

        byte[] payload = "stream-content-rollback".getBytes();
        String sha = new ChecksumService().digest(new ByteArrayInputStream(payload)).sha256();

        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult(sha, "md5", (long) payload.length)));

        when(repository.save(any(Artifact.class))).thenThrow(new RuntimeException("Simulated database failure during commit"));

        String key = "idemp-key-rollback";
        ArtifactService.UploadCommand cmd = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user1", key, null);

        assertThatThrownBy(() -> txService.upload(cmd, new ByteArrayInputStream(payload)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Simulated database failure during commit");

        io.translab.tantor.artifact.dto.ArtifactResponse cached = idempService.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(cached).isNull();
    }

    @Test
    void noSuccessRecordedWhenTransactionSynchronizationUnavailable() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        Path tempDir = java.nio.file.Files.createTempDirectory("idemp-nosync-test");
        properties.setBasePath(tempDir.toString());
        UploadIdempotencyService idempService = new UploadIdempotencyService(properties, mapper);
        idempService.init();

        ArtifactService unmanagedService = new ArtifactService(
                repository, storageService, manifestService, properties, packageValidator, commitHelper);
        unmanagedService.setIdempotencyService(idempService);

        byte[] payload = "stream-content-nosync".getBytes();
        String sha = new ChecksumService().digest(new ByteArrayInputStream(payload)).sha256();

        when(storageService.storeTemporarily(any(), any()))
                .thenReturn(new StorageService.TempStoreResult(Path.of("temp"), new ChecksumResult(sha, "md5", (long) payload.length)));

        String key = "idemp-key-nosync";
        ArtifactService.UploadCommand cmd = new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", null, Map.of(), false, "user1", key, null);

        assertThatThrownBy(() -> unmanagedService.upload(cmd, new ByteArrayInputStream(payload)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Transaction synchronization is not active. Cannot safely record idempotency success.");

        io.translab.tantor.artifact.dto.ArtifactResponse cached = idempService.claimOrAwait(key, ServiceType.KAFKA, "3.7.0",
                "kafka_2.13-3.7.0.tgz", OperationDeadline.unlimited());
        assertThat(cached).isNull();
    }

    private static ArtifactCommitHelper createTransactionalProxy(ArtifactCommitHelper target) {
        PlatformTransactionManager txManager = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };

        ProxyFactory proxyFactory = new ProxyFactory(target);
        proxyFactory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        return (ArtifactCommitHelper) proxyFactory.getProxy();
    }

    private ArtifactService.UploadCommand cmd(String declaredSha, boolean overwrite) {

        return new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", declaredSha, Map.of(), overwrite, "tester");
    }

    private InputStream data() {
        return new ByteArrayInputStream("payload".getBytes());
    }
}
