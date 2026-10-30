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

import java.nio.file.Path;
import java.util.Map;

/**
 * Executes upload-related transactional persistence and PostgreSQL advisory locking.
 * Separated from {@link ArtifactService} to avoid long-running database transactions
 * during file streaming, package validation, and malware scanning, and to ensure
 * Spring transaction proxies are properly engaged without relying on self-invocation.
 */
@Component
public class ArtifactCommitHelper {

    private static final Logger log = LoggerFactory.getLogger(ArtifactCommitHelper.class);

    private final ArtifactJpaRepository repository;
    private final StorageService storageService;
    private final ManifestService manifestService;

    public ArtifactCommitHelper(ArtifactJpaRepository repository,
                                StorageService storageService,
                                ManifestService manifestService) {
        this.repository = repository;
        this.storageService = storageService;
        this.manifestService = manifestService;
    }

    /**
     * Atomically locks the checksum and version advisory locks in deterministic order,
     * rechecks duplicates under the lock, moves the binary into final storage, writes the
     * manifest, and saves the AVAILABLE artifact record in the database.
     */
    @Transactional
    public Artifact commitAvailableArtifact(ArtifactService.UploadCommand cmd,
                                            Path tempFile,
                                            ChecksumResult cs,
                                            String artifactDir,
                                            Artifact artifact,
                                            Map<String, String> attributes) {
        // 1. Acquire advisory locks in deterministic order: checksum lock first, then version lock
        repository.acquireUploadLock("artifact-checksum:" + cs.sha256());
        repository.acquireUploadLock("artifact-version:" + cmd.serviceType() + ":" + cmd.version());

        // 2. Authoritative duplicate checks inside the transaction holding the advisory locks
        if (repository.countActiveByServiceTypeAndVersion(cmd.serviceType(), cmd.version()) > 0) {
            throw new ArtifactAlreadyExistsException("Artifact already exists for " + cmd.serviceType() + " " + cmd.version());
        }
        if (repository.countActiveByChecksumSha256(cs.sha256()) > 0) {
            throw new ArtifactAlreadyExistsException("An artifact with the same SHA-256 checksum already exists");
        }

        // 3. Move binary to final path and set status AVAILABLE
        storageService.moveToFinal(tempFile, artifactDir, cmd.fileName());
        artifact.setFullFilePath(storageService.resolveBinary(artifactDir, cmd.fileName()).toAbsolutePath().normalize().toString());
        artifact.setStatus(ArtifactStatus.AVAILABLE);

        // Register transaction rollback synchronization to clean up final binary and manifest
        // if transaction commit fails after this method returns.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        try {
                            storageService.deleteBinary(artifactDir, cmd.fileName());
                        } catch (Exception ex) {
                            log.warn("Failed to delete binary on transaction rollback for {}: {}", artifactDir, ex.getMessage());
                        }
                    }
                }
            });
        }

        // 4. Build and write manifest, and persist artifact record
        ManifestDto manifest = manifestService.build(artifact, attributes);
        String manifestJson = manifestService.toJson(manifest);
        storageService.writeManifest(artifactDir, manifestJson);
        return repository.save(artifact);
    }

    /**
     * Persists a FAILED artifact record in its own short transaction for audit and forensic tracking.
     */
    @Transactional
    public Artifact recordFailedArtifact(Artifact artifact) {
        return repository.save(artifact);
    }
}
