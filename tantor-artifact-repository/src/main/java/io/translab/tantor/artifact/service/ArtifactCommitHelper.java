package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.dto.ChecksumResult;
import io.translab.tantor.artifact.dto.ManifestDto;
import io.translab.tantor.artifact.exception.ArtifactAlreadyExistsException;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.translab.tantor.artifact.dto.ArtifactResponse;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Executes upload-related transactional persistence and PostgreSQL advisory
 * locking.
 * Separated from {@link ArtifactService} to avoid long-running database
 * transactions
 * during file streaming, package validation, and malware scanning, and to
 * ensure
 * Spring transaction proxies are properly engaged without relying on
 * self-invocation.
 */
@Component
public class ArtifactCommitHelper {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCommitHelper.class);

    private final ArtifactJpaRepository repository;
    private final StorageService storageService;
    private final ManifestService manifestService;
    private final ArtifactReconciliationService reconciliationService;
    private UploadIdempotencyService idempotencyService;

    public ArtifactCommitHelper(ArtifactJpaRepository repository,
            StorageService storageService,
            ManifestService manifestService) {
        this(repository, storageService, manifestService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ArtifactCommitHelper(ArtifactJpaRepository repository,
            StorageService storageService,
            ManifestService manifestService,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ArtifactReconciliationService reconciliationService) {
        this.repository = repository;
        this.storageService = storageService;
        this.manifestService = manifestService;
        this.reconciliationService = reconciliationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdempotencyService(UploadIdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    /**
     * Atomically locks the checksum and version advisory locks in deterministic
     * order,
     * rechecks duplicates under the lock, moves the binary into final storage,
     * writes the
     * manifest, and saves the AVAILABLE artifact record in the database.
     */
    @Transactional
    public Artifact commitAvailableArtifact(ArtifactService.UploadCommand cmd,
            Path tempFile,
            ChecksumResult cs,
            String artifactDir,
            Artifact artifact,
            Map<String, String> attributes) {
        if (cmd.deadline() != null) {
            cmd.deadline().checkNotExceeded("commit artifact");
        }

        // 1. Acquire advisory locks in deterministic order: checksum lock first, then
        // version lock
        repository.acquireUploadLock("artifact-checksum:" + cs.sha256());
        repository.acquireUploadLock("artifact-version:" + cmd.serviceType() + ":" + cmd.version());

        // 2. Authoritative duplicate checks inside the transaction holding the advisory
        // locks
        java.util.List<Runnable> postCommitCleanups = new java.util.ArrayList<>();
        if (!cmd.overwrite()) {
            if (repository.countActiveByServiceTypeAndVersion(cmd.serviceType(), cmd.version()) > 0) {
                throw new ArtifactAlreadyExistsException(
                        "Artifact already exists for " + cmd.serviceType() + " " + cmd.version());
            }
            if (repository.countActiveByChecksumSha256(cs.sha256()) > 0) {
                throw new ArtifactAlreadyExistsException("An artifact with the same SHA-256 checksum already exists");
            }
        } else {
            List<Artifact> existingActive = repository.findActiveByServiceTypeAndVersion(cmd.serviceType(),
                    cmd.version());
            if (!existingActive.isEmpty()) {
                // Overwrite allowed: check if identical or replacing
                for (Artifact existing : existingActive) {
                    if (existing.getChecksumSha256().equalsIgnoreCase(cs.sha256())) {
                        log.info("Identical artifact exists for {} {} with matching SHA-256; returning existing",
                                cmd.serviceType(), cmd.version());
                        storageService.deleteTemp(tempFile);
                        recordIdempotencySuccessAfterCommit(cmd, existing);
                        return existing;
                    }
                    // Different content: soft-delete previous active version
                    Artifact deleteEntry = actionFrom(existing, "DELETE", ArtifactStatus.DELETED, cmd.createdBy());
                    repository.save(deleteEntry);
                    String oldRelDir = existing.getRelativePath().substring(0,
                            existing.getRelativePath().lastIndexOf('/'));
                    String oldFileName = existing.getFileName();
                    postCommitCleanups.add(() -> {
                        try {
                            storageService.deleteBinary(oldRelDir, oldFileName);
                        } catch (Exception ex) {
                            log.warn("Failed to delete overwritten binary in {}: {}", oldRelDir, ex.getMessage());
                            if (reconciliationService != null) {
                                reconciliationService
                                        .enqueueForCleanup(storageService.resolveBinary(oldRelDir, oldFileName));
                            }
                        }
                    });
                }
            }

            List<Artifact> existingWithChecksum = repository.findActiveByChecksumSha256(cs.sha256());
            if (!existingWithChecksum.isEmpty()) {
                boolean sameCoordinate = existingWithChecksum.stream().allMatch(
                        a -> a.getServiceType() == cmd.serviceType() && a.getVersion().equals(cmd.version()));
                if (!sameCoordinate) {
                    throw new ArtifactAlreadyExistsException(
                            "An artifact with the same SHA-256 checksum already exists");
                }
            }
        }

        // 3. Move binary to final path and set status AVAILABLE
        storageService.moveToFinal(tempFile, artifactDir, cmd.fileName());
        artifact.setFullFilePath(
                storageService.resolveBinary(artifactDir, cmd.fileName()).toAbsolutePath().normalize().toString());
        artifact.setStatus(ArtifactStatus.AVAILABLE);

        // Register transaction rollback & commit synchronizations:
        // - on commit: clean up superseded old binaries
        // - on rollback: clean up newly moved binary
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (Runnable cleanup : postCommitCleanups) {
                        cleanup.run();
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            storageService.deleteBinary(artifactDir, cmd.fileName());
                        } catch (Exception ex) {
                            log.warn("Failed to delete binary on transaction rollback for {}: {}", artifactDir,
                                    ex.getMessage());
                            if (reconciliationService != null) {
                                reconciliationService
                                        .enqueueForCleanup(storageService.resolveBinary(artifactDir, cmd.fileName()));
                            }
                        }
                    }
                }
            });
        } else {
            for (Runnable cleanup : postCommitCleanups) {
                cleanup.run();
            }
        }

        // 4. Build and write manifest, and persist artifact record
        ManifestDto manifest = manifestService.build(artifact, attributes);
        String manifestJson = manifestService.toJson(manifest);
        storageService.writeManifest(artifactDir, manifestJson);
        Artifact saved = repository.save(artifact);
        recordIdempotencySuccessAfterCommit(cmd, saved);
        return saved;
    }

    private void recordIdempotencySuccessAfterCommit(ArtifactService.UploadCommand cmd, Artifact artifact) {
        if (idempotencyService == null || cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException(
                    "Transaction synchronization is not active. Cannot safely record idempotency success.");
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    idempotencyService.recordSuccess(cmd.idempotencyKey(), artifact, ArtifactResponse.from(artifact));
                    log.info("Idempotency success recorded after database commit for key '{}'", cmd.idempotencyKey());
                } catch (Exception e) {

                    // The artifact transaction has already committed, .
                    // Do not report the committed artifact as a failed report .
                    log.error("Artifact commited, but idempotency record persistence failed for key: '{}'",
                            cmd.idempotencyKey(), e);
                }
            }

        });
    }

    /**
     * Persists a FAILED artifact record in its own short transaction for audit and
     * forensic tracking.
     */
    @Transactional
    public Artifact recordFailedArtifact(Artifact artifact) {
        return repository.save(artifact);
    }

    private Artifact actionFrom(Artifact source, String action, ArtifactStatus status, String actor) {
        Artifact event = new Artifact();
        event.setId(java.util.UUID.randomUUID());
        event.setRootArtifactId(source.getRootArtifactId() == null ? source.getId() : source.getRootArtifactId());
        event.setAction(action);
        event.setServiceType(source.getServiceType());
        event.setVersion(source.getVersion());
        event.setFileName(source.getFileName());
        event.setRelativePath(source.getRelativePath());
        event.setFullFilePath(source.getFullFilePath());
        event.setFileSizeBytes(source.getFileSizeBytes());
        event.setContentType(source.getContentType());
        event.setChecksumSha256(source.getChecksumSha256());
        event.setChecksumMd5(source.getChecksumMd5());
        event.setStatus(status);
        event.setCreatedBy(actor == null || actor.isBlank() ? "system" : actor);
        event.setUpdatedBy(event.getCreatedBy());
        return event;
    }
}
