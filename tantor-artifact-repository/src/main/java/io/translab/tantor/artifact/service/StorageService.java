package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ChecksumResult;
import io.translab.tantor.artifact.exception.StorageException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Owns the on-disk repository layout:
 *
 * <pre>
 *   {basePath}/artifacts/{serviceDir}/{version}[/{classifier}]/{fileName}
 *   {basePath}/artifacts/{serviceDir}/{version}[/{classifier}]/manifest.json
 * </pre>
 *
 * Writes go to a temp file first and are atomically moved into place, so a
 * crashed upload never leaves a half-written binary that an agent might pull.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final String ARTIFACTS_DIR = "artifacts";
    public static final String MANIFEST_FILE = "manifest.json";

    private final StorageProperties properties;
    private final ChecksumService checksumService;
    private Path artifactsRoot;

    public StorageService(StorageProperties properties, ChecksumService checksumService) {
        this.properties = properties;
        this.checksumService = checksumService;
    }

    @PostConstruct
    void init() {
        try {
            this.artifactsRoot = Paths.get(properties.getBasePath(), ARTIFACTS_DIR)
                    .toAbsolutePath().normalize();
            for (ServiceType type : ServiceType.values()) {
                Files.createDirectories(artifactsRoot.resolve(type.directory()));
            }
            log.info("Tantor artifact repository initialised at {}", artifactsRoot);
        } catch (IOException e) {
            throw new StorageException("Unable to initialise repository at " + properties.getBasePath(), e);
        }
    }

    /** Repository-relative directory for a coordinate, e.g. {@code kafka/3.7.0/2.13}. */
    public String relativeDir(ServiceType type, String version, String classifier) {
        StringBuilder sb = new StringBuilder(type.directory()).append('/').append(version);
        if (classifier != null && !classifier.isBlank()) {
            sb.append('/').append(classifier);
        }
        return sb.toString();
    }

    /**
     * Stream an upload into the repository, computing checksums in the same pass.
     *
     * @return the computed checksums and byte count
     */
    public ChecksumResult store(ServiceType type, String version, String classifier,
                                String fileName, InputStream data) {
        String relDir = relativeDir(type, version, classifier);
        Path targetDir = resolveSafe(relDir);
        try {
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(fileName);
            Path tmp = Files.createTempFile(targetDir, ".upload-", ".part");
            ChecksumResult result;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                result = checksumService.copyAndDigest(data, out);
            }
            Files.move(tmp, target,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            log.info("Stored {} ({} bytes, sha256={})", relDir + "/" + fileName,
                    result.sizeBytes(), result.sha256());
            return result;
        } catch (IOException e) {
            throw new StorageException("Failed to store " + relDir + "/" + fileName, e);
        }
    }

    /** Write the per-artifact manifest.json next to the binary. */
    public void writeManifest(String relativeDir, String manifestJson) {
        Path dir = resolveSafe(relativeDir);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(MANIFEST_FILE), manifestJson);
        } catch (IOException e) {
            throw new StorageException("Failed to write manifest for " + relativeDir, e);
        }
    }

    /** Resolve a stored binary to an absolute, validated path. */
    public Path resolveBinary(String relativeDir, String fileName) {
        return resolveSafe(relativeDir).resolve(fileName);
    }

    public boolean exists(String relativeDir, String fileName) {
        return Files.isRegularFile(resolveBinary(relativeDir, fileName));
    }

    public InputStream openStream(String relativeDir, String fileName) {
        try {
            return Files.newInputStream(resolveBinary(relativeDir, fileName));
        } catch (IOException e) {
            throw new StorageException("Failed to open " + relativeDir + "/" + fileName, e);
        }
    }

    /** Delete the binary (and manifest) for a coordinate; directory left in place. */
    public void deleteBinary(String relativeDir, String fileName) {
        try {
            Files.deleteIfExists(resolveBinary(relativeDir, fileName));
            Files.deleteIfExists(resolveSafe(relativeDir).resolve(MANIFEST_FILE));
        } catch (IOException e) {
            throw new StorageException("Failed to delete " + relativeDir + "/" + fileName, e);
        }
    }

    public Path artifactsRoot() {
        return artifactsRoot;
    }

    /**
     * Resolve a repository-relative path and guarantee it stays inside the
     * repository root, defeating any {@code ../} traversal in a coordinate.
     */
    private Path resolveSafe(String relativeDir) {
        Path resolved = artifactsRoot.resolve(relativeDir).normalize();
        if (!resolved.startsWith(artifactsRoot)) {
            throw new StorageException("Path traversal blocked for: " + relativeDir);
        }
        return resolved;
    }
}
