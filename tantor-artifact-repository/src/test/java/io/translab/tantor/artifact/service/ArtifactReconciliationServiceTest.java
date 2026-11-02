package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ArtifactReconciliationServiceTest {

    private ArtifactJpaRepository repository;
    private StorageService storageService;
    private StorageProperties properties;
    private TemporaryStorageBudget storageBudget;
    private ArtifactReconciliationService reconciler;

    @TempDir
    Path repoDir;

    @BeforeEach
    void setUp() {
        repository = mock(ArtifactJpaRepository.class);
        properties = new StorageProperties();
        properties.setBasePath(repoDir.toString());
        properties.setStaleFileThreshold(Duration.ofMinutes(10));

        storageBudget = new TemporaryStorageBudget(properties);
        storageService = new StorageService(properties, new ChecksumService(), storageBudget);
        storageService.init();

        reconciler = new ArtifactReconciliationService(repository, storageService, properties, storageBudget);
    }

    @Test
    void staleTempFilesCleanedUpWhileActivePreserved() throws IOException {
        Path artifactsRoot = storageService.artifactsRoot();

        // Stale temp file (15 mins old, threshold is 10 mins)
        Path staleTemp = Files.createFile(artifactsRoot.resolve(".upload-stale-test.tgz"));
        Files.setLastModifiedTime(staleTemp, FileTime.from(Instant.now().minus(Duration.ofMinutes(15))));

        // Fresh temp file (2 mins old)
        Path freshTemp = Files.createFile(artifactsRoot.resolve(".upload-fresh-test.tgz"));
        Files.setLastModifiedTime(freshTemp, FileTime.from(Instant.now().minus(Duration.ofMinutes(2))));

        // Non-temp file
        Path regularFile = Files.createFile(artifactsRoot.resolve("regular-file.txt"));

        int cleaned = reconciler.reconcileStaleTempFiles();

        assertThat(cleaned).isEqualTo(1);
        assertThat(Files.exists(staleTemp)).isFalse();
        assertThat(Files.exists(freshTemp)).isTrue();
        assertThat(Files.exists(regularFile)).isTrue();
    }

    @Test
    void missingBinariesMarkedCorrupted() {
        Artifact a = new Artifact();
        a.setId(UUID.randomUUID());
        a.setServiceType(ServiceType.KAFKA);
        a.setVersion("3.7.0");
        a.setFileName("kafka_2.13-3.7.0.tgz");
        a.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        a.setStatus(ArtifactStatus.AVAILABLE);
        a.setAction("UPLOAD");

        when(repository.findByStatusAndAction(eq(ArtifactStatus.AVAILABLE), eq("UPLOAD"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));

        int detected = reconciler.reconcileMissingBinaries();

        assertThat(detected).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(ArtifactStatus.CORRUPTED);
        verify(repository, times(1)).save(a);
        verify(repository, never()).findByStatusAndAction(any(ArtifactStatus.class), anyString());
    }

    @Test
    void paginatedQueryFailureLogsAndDoesNotUseUnboundedFallback() {
        when(repository.findByStatusAndAction(eq(ArtifactStatus.AVAILABLE), eq("UPLOAD"), any(Pageable.class)))
                .thenThrow(new RuntimeException("Simulated database failure during pagination"));

        int detected = reconciler.reconcileMissingBinaries();

        assertThat(detected).isEqualTo(0);
        // Verify unbounded fallback is never called
        verify(repository, never()).findByStatusAndAction(any(ArtifactStatus.class), anyString());
    }

    @Test
    void scheduledReconciliationRetriesPaginatedMissingBinaries() {
        Artifact a = new Artifact();
        a.setId(UUID.randomUUID());
        a.setServiceType(ServiceType.KAFKA);
        a.setVersion("3.7.0");
        a.setFileName("kafka_2.13-3.7.0.tgz");
        a.setRelativePath("kafka/3.7.0/kafka_2.13-3.7.0.tgz");
        a.setStatus(ArtifactStatus.AVAILABLE);
        a.setAction("UPLOAD");

        when(repository.findByStatusAndAction(eq(ArtifactStatus.AVAILABLE), eq("UPLOAD"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));

        reconciler.scheduledReconciliation();

        assertThat(a.getStatus()).isEqualTo(ArtifactStatus.CORRUPTED);
        verify(repository, times(1)).save(a);
        verify(repository, never()).findByStatusAndAction(any(ArtifactStatus.class), anyString());
    }

    @Test
    void retryPendingCleanupsRetriesDeferredFiles() throws IOException {
        Path tempFile = Files.createFile(repoDir.resolve("deferred-to-clean.tmp"));
        assertThat(Files.exists(tempFile)).isTrue();

        reconciler.enqueueForCleanup(tempFile);
        int retried = reconciler.retryPendingCleanups();

        assertThat(retried).isEqualTo(1);
        assertThat(Files.exists(tempFile)).isFalse();
    }

    @Test
    void activeUploadIsNotDeletedDuringReconciliation() throws Exception {
        // Active upload staged via real storageService
        StorageService.TempStoreResult tempStore = storageService.storeTemporarily(
                "active-upload.tgz", new java.io.ByteArrayInputStream("active-data".getBytes()));
        Path activeUpload = tempStore.tempPath();

        // Artificially age the active upload so it would be considered stale if not
        // protected
        Files.setLastModifiedTime(activeUpload, FileTime.from(Instant.now().minus(Duration.ofHours(2))));

        assertThat(storageService.isActiveUpload(activeUpload)).isTrue();

        int cleaned = reconciler.reconcileStaleTempFiles();
        assertThat(cleaned).isEqualTo(0);
        assertThat(Files.exists(activeUpload)).isTrue();

        tempStore.close();
    }

    @Test
    void retryPendingCleanupsCountsSuccessfulDirectoryDeletion() throws Exception {
        Path pendingDirectory = storageService.artifactsRoot()
                .resolve(".upload-success-test");

        Files.createDirectories(pendingDirectory);
        Files.writeString(pendingDirectory.resolve("test.txt"), "test data");

        reconciler.enqueueForCleanup(pendingDirectory);

        int retried = reconciler.retryPendingCleanups();

        assertThat(retried).isEqualTo(1);
        assertThat(Files.exists(pendingDirectory)).isFalse();
    }

    @Test
    void retryPendingCleanupsDoesNotCountFailedDeletionAsSuccessful() throws Exception {
        Path pendingDirectory = storageService.artifactsRoot()
                .resolve(".upload-pending-test");

        Files.createDirectories(pendingDirectory);

        Path nestedFile = pendingDirectory.resolve("test.txt");
        Files.writeString(nestedFile, "test data");

        ArtifactReconciliationService service = spy(reconciler);

        service.enqueueForCleanup(pendingDirectory);

        doThrow(new IOException("Simulated deletion failure"))
                .when(service).deletePath(nestedFile);

        int retried = service.retryPendingCleanups();

        assertThat(retried).isZero();
        assertThat(Files.exists(pendingDirectory)).isTrue();
        assertThat(Files.exists(nestedFile)).isTrue();
    }

    @Test
    void reconcileStaleTempFilesDoesNotCountFailedRecursiveDeletion() throws Exception {
        Path tempDirectory = storageService.artifactsRoot()
                .resolve(".upload-test-directory");

        Files.createDirectories(tempDirectory);

        Path nestedFile = tempDirectory.resolve("test.txt");
        Files.writeString(nestedFile, "test data");
        Files.setLastModifiedTime(tempDirectory, FileTime.from(Instant.now().minus(Duration.ofMinutes(15))));

        ArtifactReconciliationService service = spy(reconciler);

        doThrow(new IOException("Simulated deletion failure"))
                .when(service).deletePath(nestedFile);

        int deleted = service.reconcileStaleTempFiles();

        assertThat(deleted).isZero();
        assertThat(Files.exists(tempDirectory)).isTrue();
        assertThat(Files.exists(nestedFile)).isTrue();
    }

    @Test
    void retryPendingCleanupsDoesNotProcessQueuePathAgainFromJournal() throws Exception {
        Path directory = storageService.artifactsRoot()
                .resolve(".upload-duplicate-test");

        Files.createDirectories(directory);
        Path nestedFile = Files.writeString(directory.resolve("test.txt"), "data");

        ArtifactReconciliationService service = spy(reconciler);
        service.enqueueForCleanup(directory);

        // Keep the journal marker so the journal phase encounters the same path.
        doNothing().when(service).removeJournalMarker(directory);

        int retried = service.retryPendingCleanups();

        assertThat(retried).isEqualTo(1);
        assertThat(Files.exists(directory)).isFalse();

        // Recursive deletion should happen only once.
        verify(service, times(1)).deletePath(nestedFile);
        verify(service, times(1)).deletePath(directory);
    }

    @Test
    void retryPendingCleanupsRetainsEntryWhenMarkerDeletionFails() throws Exception {
        Path target = Files.createFile(
                storageService.artifactsRoot().resolve("marker-failure-test.tmp"));

        ArtifactReconciliationService service = spy(reconciler);
        service.enqueueForCleanup(target);
        // First cycle: target deletion succeeds, but marker deletion fails.
        doThrow(new IOException("Simulated marker deletion failure"))
                .when(service).removeJournalMarker(target);

        int retried = service.retryPendingCleanups();

        assertThat(retried).isEqualTo(1);
        assertThat(Files.exists(target)).isFalse();

        Path journalDir = storageService.artifactsRoot().resolve(".pending-cleanups");

        try (var markers = Files.list(journalDir)) {
            assertThat(markers).hasSize(1);
        }
        // Second cycle: allow actual marker deletion.
        doCallRealMethod().when(service).removeJournalMarker(target);

        service.retryPendingCleanups();

        try (var markers = Files.list(journalDir)) {
            assertThat(markers).isEmpty();
        }
    }
}
