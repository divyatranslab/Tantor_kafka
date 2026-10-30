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
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

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

    private ArtifactService.UploadCommand cmd(String declaredSha, boolean overwrite) {
        return new ArtifactService.UploadCommand(
                ServiceType.KAFKA, "kafka", "3.7.0", null, null, "kafka_2.13-3.7.0.tgz",
                "application/gzip", "test", declaredSha, Map.of(), overwrite, "tester");
    }

    private InputStream data() {
        return new ByteArrayInputStream("payload".getBytes());
    }
}
