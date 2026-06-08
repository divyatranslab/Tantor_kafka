package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactDownloadLog;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ChecksumResult;
import io.translab.tantor.artifact.dto.ManifestDto;
import io.translab.tantor.artifact.exception.ArtifactAlreadyExistsException;
import io.translab.tantor.artifact.exception.ArtifactNotFoundException;
import io.translab.tantor.artifact.exception.ChecksumMismatchException;
import io.translab.tantor.artifact.repository.ArtifactDownloadLogRepository;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates the database index ({@link ArtifactJpaRepository}) with the
 * on-disk store ({@link StorageService}) so the two never drift. Every mutating
 * method either fully succeeds or rolls back both sides.
 */
@Service
public class ArtifactService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactService.class);

    private final ArtifactJpaRepository repository;
    private final ArtifactDownloadLogRepository downloadLogRepository;
    private final StorageService storageService;
    private final ManifestService manifestService;
    private final StorageProperties properties;

    public ArtifactService(ArtifactJpaRepository repository,
                           ArtifactDownloadLogRepository downloadLogRepository,
                           StorageService storageService,
                           ManifestService manifestService,
                           StorageProperties properties) {
        this.repository = repository;
        this.downloadLogRepository = downloadLogRepository;
        this.storageService = storageService;
        this.manifestService = manifestService;
        this.properties = properties;
    }

    /** Parameters for an upload. */
    public record UploadCommand(
            ServiceType serviceType,
            String name,
            String version,
            String classifier,
            String fileName,
            String contentType,
            String description,
            String declaredSha256,
            Map<String, String> attributes,
            boolean overwrite,
            String createdBy
    ) {}

    @Transactional
    public Artifact upload(UploadCommand cmd, InputStream data) {
        String classifier = blankToNull(cmd.classifier());
        boolean exists = repository.existsByServiceTypeAndVersionAndClassifier(
                cmd.serviceType(), cmd.version(), classifier);
        if (exists && !cmd.overwrite()) {
            throw new ArtifactAlreadyExistsException(
                    "Artifact already exists for %s %s%s (set overwrite=true to replace)"
                            .formatted(cmd.serviceType(), cmd.version(),
                                    classifier == null ? "" : " [" + classifier + "]"));
        }

        // 1. Stream bytes to disk and compute checksums in one pass.
        ChecksumResult cs = storageService.store(
                cmd.serviceType(), cmd.version(), classifier, cmd.fileName(), data);

        // 2. Enforce a declared checksum if the uploader supplied one.
        if (properties.isEnforceChecksum() && cmd.declaredSha256() != null
                && !cmd.declaredSha256().equalsIgnoreCase(cs.sha256())) {
            String relDir = storageService.relativeDir(cmd.serviceType(), cmd.version(), classifier);
            storageService.deleteBinary(relDir, cmd.fileName());
            throw new ChecksumMismatchException(
                    "Declared SHA-256 %s does not match computed %s"
                            .formatted(cmd.declaredSha256(), cs.sha256()));
        }

        // 3. Upsert the index row.
        Artifact artifact = exists
                ? repository.findByServiceTypeAndVersionAndClassifier(
                        cmd.serviceType(), cmd.version(), classifier).orElseThrow()
                : new Artifact();

        artifact.setServiceType(cmd.serviceType());
        artifact.setName(cmd.name());
        artifact.setVersion(cmd.version());
        artifact.setClassifier(classifier);
        artifact.setFileName(cmd.fileName());
        artifact.setRelativePath(storageService.relativeDir(cmd.serviceType(), cmd.version(), classifier)
                + "/" + cmd.fileName());
        artifact.setFileSizeBytes(cs.sizeBytes());
        artifact.setContentType(cmd.contentType() != null ? cmd.contentType() : "application/gzip");
        artifact.setChecksumSha256(cs.sha256());
        artifact.setChecksumMd5(cs.md5());
        artifact.setDescription(cmd.description());
        artifact.setCreatedBy(cmd.createdBy() != null ? cmd.createdBy() : "system");
        artifact.setStatus(ArtifactStatus.AVAILABLE);

        // 4. Build manifest, persist on the row and beside the binary.
        ManifestDto manifest = manifestService.build(artifact, cmd.attributes());
        String manifestJson = manifestService.toJson(manifest);
        artifact.setManifest(manifestJson);

        Artifact saved = repository.save(artifact);
        storageService.writeManifest(
                storageService.relativeDir(cmd.serviceType(), cmd.version(), classifier), manifestJson);

        log.info("Artifact {} registered: {} {} ({} bytes)",
                saved.getId(), saved.getServiceType(), saved.getVersion(), saved.getFileSizeBytes());
        return saved;
    }

    @Transactional(readOnly = true)
    public Artifact get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ArtifactNotFoundException("No artifact with id " + id));
    }

    @Transactional(readOnly = true)
    public Page<Artifact> list(ServiceType serviceType, ArtifactStatus status, Pageable pageable) {
        return repository.search(serviceType, status, pageable);
    }

    /** Resolve the on-disk path for a downloadable artifact. */
    @Transactional(readOnly = true)
    public Path resolveForDownload(UUID id) {
        Artifact a = get(id);
        if (a.getStatus() != ArtifactStatus.AVAILABLE) {
            throw new ArtifactNotFoundException(
                    "Artifact " + id + " is not downloadable (status=" + a.getStatus() + ")");
        }
        return storageService.resolveBinary(
                a.getRelativePath().substring(0, a.getRelativePath().lastIndexOf('/')),
                a.getFileName());
    }

    @Transactional
    public void logDownload(UUID artifactId, String remoteAddr, String by, boolean verified) {
        ArtifactDownloadLog entry = new ArtifactDownloadLog();
        entry.setArtifactId(artifactId);
        entry.setRemoteAddr(remoteAddr);
        entry.setDownloadedBy(by != null ? by : "agent");
        entry.setVerifiedChecksum(verified);
        downloadLogRepository.save(entry);
    }

    /**
     * Re-read the stored binary and confirm it still matches the recorded
     * SHA-256. Flips the row to CORRUPTED if it does not.
     */
    @Transactional
    public boolean verifyIntegrity(UUID id) {
        Artifact a = get(id);
        String relDir = a.getRelativePath().substring(0, a.getRelativePath().lastIndexOf('/'));
        ChecksumResult cs;
        try (InputStream in = storageService.openStream(relDir, a.getFileName())) {
            cs = new ChecksumService().digest(in);
        } catch (Exception e) {
            a.setStatus(ArtifactStatus.CORRUPTED);
            repository.save(a);
            return false;
        }
        boolean ok = cs.sha256().equalsIgnoreCase(a.getChecksumSha256());
        if (!ok) {
            log.warn("Integrity check FAILED for {}: expected {} got {}",
                    id, a.getChecksumSha256(), cs.sha256());
            a.setStatus(ArtifactStatus.CORRUPTED);
            repository.save(a);
        }
        return ok;
    }

    /** Soft-delete: remove the binary from disk, mark the row DELETED. */
    @Transactional
    public void delete(UUID id) {
        Artifact a = get(id);
        String relDir = a.getRelativePath().substring(0, a.getRelativePath().lastIndexOf('/'));
        storageService.deleteBinary(relDir, a.getFileName());
        a.setStatus(ArtifactStatus.DELETED);
        repository.save(a);
        log.info("Artifact {} soft-deleted", id);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
