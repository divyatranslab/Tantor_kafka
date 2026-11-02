package io.translab.tantor.artifact.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ServiceType;
import io.translab.tantor.artifact.dto.ArtifactResponse;
import io.translab.tantor.artifact.exception.ChecksumMismatchException;
import io.translab.tantor.artifact.exception.StorageException;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.translab.tantor.artifact.util.OperationDeadline;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import io.translab.tantor.artifact.exception.OperationTimeoutException;

/**
 * Manages upload idempotency to prevent repeated expensive staging, validation,
 * and ClamAV scanning for identical requests, while coordinating concurrent
 * requests.
 */
@Service
public class UploadIdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(UploadIdempotencyService.class);
    private static final String IDEMPOTENCY_DIR = ".idempotency";

    private final StorageProperties properties;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, InFlightUpload> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IdempotencyRecord> completed = new ConcurrentHashMap<>();

    public UploadIdempotencyService(StorageProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public record IdempotencyRecord(
            String idempotencyKey,
            ServiceType serviceType,
            String version,
            String fileName,
            String sha256,
            ArtifactResponse response,
            OffsetDateTime recordedAt) {
    }

    private static final class InFlightUpload {
        private final String idempotencyKey;
        private final ServiceType serviceType;
        private final String version;
        private final String fileName;
        private final String declaredSha256;
        private final CompletableFuture<ArtifactResponse> future = new CompletableFuture<>();

        private InFlightUpload(String idempotencyKey, ServiceType serviceType, String version, String fileName,
                String declaredSha256) {
            this.idempotencyKey = idempotencyKey;
            this.serviceType = serviceType;
            this.version = version;
            this.fileName = fileName;
            this.declaredSha256 = declaredSha256;
        }
    }

    @PostConstruct
    void init() {
        loadPersistedRecords();
    }

    public ArtifactResponse claimOrAwait(String key, ServiceType serviceType, String version,
            String fileName, OperationDeadline deadline) {
        return claimOrAwait(key, serviceType, version, fileName, null, (java.util.function.Supplier<String>) null,
                deadline);
    }

    public ArtifactResponse claimOrAwait(String key, ServiceType serviceType, String version,
            String fileName, String declaredSha256, OperationDeadline deadline) {
        return claimOrAwait(key, serviceType, version, fileName, declaredSha256,
                (java.util.function.Supplier<String>) null, deadline);
    }

    public ArtifactResponse claimOrAwait(String key, ServiceType serviceType, String version,
            String fileName, String declaredSha256, String actualFingerprint,
            OperationDeadline deadline) {
        return claimOrAwait(key, serviceType, version, fileName, declaredSha256,
                actualFingerprint != null ? () -> actualFingerprint : null, deadline);
    }

    /**
     * Attempts to acquire an idempotency lease for the given key and request
     * details.
     * Verifies the actual content fingerprint for every request sharing this key.
     *
     * @return an already completed {@link ArtifactResponse} if this is an identical
     *         retry,
     *         or {@code null} if the caller has successfully claimed the right to
     *         process.
     */
    public ArtifactResponse claimOrAwait(String key, ServiceType serviceType, String version,
            String fileName, String declaredSha256,
            java.util.function.Supplier<String> actualFingerprintSupplier,
            OperationDeadline deadline) {
        if (key == null || key.isBlank()) {
            return null;
        }

        // 1. Check completed cache (memory first, then durable disk)
        IdempotencyRecord cached = completed.get(key);
        if (cached == null) {
            cached = loadRecordFromDisk(key);
            if (cached != null) {
                completed.put(key, cached);
            }
        }
        if (cached != null) {
            validateCompatibility(cached, serviceType, version, fileName, declaredSha256);
            verifyFingerprint(cached.sha256(), declaredSha256, actualFingerprintSupplier, key);
            log.info("Idempotent hit for key '{}': returning previous result for {} {}", key, serviceType, version);
            return cached.response();
        }

        // 2. Coordinate in-flight requests
        InFlightUpload myUpload = new InFlightUpload(key, serviceType, version, fileName, declaredSha256);
        InFlightUpload existing = inFlight.putIfAbsent(key, myUpload);
        if (existing == null) {
            // Caller won the claim to execute
            return null;
        }

        // Another request is actively processing this idempotency key
        validateCompatibility(existing.serviceType, existing.version, existing.fileName, key, serviceType, version,
                fileName);
        if (declaredSha256 != null && existing.declaredSha256 != null
                && !declaredSha256.equalsIgnoreCase(existing.declaredSha256)) {
            throw new IllegalArgumentException(
                    "Idempotency key '%s' was already used for different content (checksum %s vs %s)"
                            .formatted(key, existing.declaredSha256, declaredSha256));
        }
        log.info("Concurrent request for in-flight idempotency key '{}': awaiting completion", key);

        long waitMs = deadline != null ? Math.min(deadline.remainingMillis(), properties.getUploadTimeoutMs())
                : properties.getUploadTimeoutMs();
        if (waitMs <= 0) {
            throw new OperationTimeoutException("Timeout awaiting concurrent upload with idempotency key " + key);
        }

        ArtifactResponse response;
        try {
            response = existing.future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new OperationTimeoutException("Timeout awaiting concurrent upload with idempotency key " + key, e);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re)
                throw re;
            throw new RuntimeException("Concurrent upload failed: " + cause.getMessage(), cause);
        }

        verifyFingerprint(response != null ? response.sha256() : null, declaredSha256, actualFingerprintSupplier, key);
        return response;
    }

    /**
     * Mark the upload as completed and persist the record for future idempotent
     * retries.
     * Persists to disk before updating in-memory cache to guarantee durability.
     * If persistence fails, throws StorageException and notifies in-flight waiters.
     */
    public void recordSuccess(String key, Artifact saved, ArtifactResponse response) {
        if (key == null || key.isBlank() || response == null)
            return;
        if (completed.containsKey(key)) {
            return;
        }

        IdempotencyRecord record = new IdempotencyRecord(
                key,
                saved.getServiceType(),
                saved.getVersion(),
                saved.getFileName(),
                saved.getChecksumSha256(),
                response,
                OffsetDateTime.now());

        try {
            persistRecord(record);
            completed.put(key, record);
            InFlightUpload ongoing = inFlight.remove(key);
            if (ongoing != null) {
                ongoing.future.complete(response);
            }
        } catch (Exception e) {
            InFlightUpload ongoing = inFlight.remove(key);
            if (ongoing != null) {
                ongoing.future.completeExceptionally(e);
            }
            if (e instanceof RuntimeException re)
                throw re;
            throw new StorageException("Failed to persist idempotency record for key " + key, e);
        }
    }

    /**
     * Mark an in-flight upload as failed so waiting concurrent requests are
     * notified.
     */
    public void recordFailure(String key, Throwable error) {
        if (key == null || key.isBlank())
            return;
        InFlightUpload ongoing = inFlight.remove(key);
        if (ongoing != null) {
            ongoing.future.completeExceptionally(error != null ? error : new RuntimeException("Upload failed"));
        }
    }

    public int purgeExpiredRecords(java.time.Duration retention) {
        if (retention == null || retention.isNegative() || retention.isZero())
            return 0;
        Path root = Path.of(properties.getBasePath()).resolve(IDEMPOTENCY_DIR);
        if (!Files.isDirectory(root))
            return 0;

        OffsetDateTime cutoff = OffsetDateTime.now().minus(retention);
        int purged = 0;

        completed.entrySet().removeIf(entry -> {
            IdempotencyRecord rec = entry.getValue();
            return rec.recordedAt() != null && rec.recordedAt().isBefore(cutoff);
        });

        try (Stream<Path> stream = Files.list(root)) {
            java.util.List<Path> files = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
            for (Path p : files) {
                try {
                    IdempotencyRecord rec = objectMapper.readValue(p.toFile(), IdempotencyRecord.class);
                    if (rec != null && rec.recordedAt() != null && rec.recordedAt().isBefore(cutoff)) {
                        Files.deleteIfExists(p);
                        purged++;
                    }
                } catch (Exception e) {
                    try {
                        if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff.toInstant())) {
                            Files.deleteIfExists(p);
                            purged++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to purge expired idempotency records: {}", e.getMessage());
        }
        return purged;
    }

    private void validateCompatibility(IdempotencyRecord record, ServiceType serviceType, String version,
            String fileName, String declaredSha256) {
        validateCompatibility(record.serviceType(), record.version(), record.fileName(), record.idempotencyKey(),
                serviceType, version, fileName);
        if (declaredSha256 != null && record.sha256() != null && !record.sha256().equalsIgnoreCase(declaredSha256)) {
            throw new IllegalArgumentException(
                    "Idempotency key '%s' was already used for different content (checksum %s vs %s)"
                            .formatted(record.idempotencyKey(), record.sha256(), declaredSha256));
        }
    }

    private void verifyFingerprint(String expectedSha256, String declaredSha256,
            java.util.function.Supplier<String> actualFingerprintSupplier, String key) {
        if (declaredSha256 != null && expectedSha256 != null && !expectedSha256.equalsIgnoreCase(declaredSha256)) {
            throw new IllegalArgumentException(
                    "Idempotency key '%s' was already used for different content (checksum %s vs %s)"
                            .formatted(key, expectedSha256, declaredSha256));
        }

        if (actualFingerprintSupplier != null) {
            String actual = actualFingerprintSupplier.get();
            if (actual != null && expectedSha256 != null && !expectedSha256.equalsIgnoreCase(actual)) {
                throw new IllegalArgumentException(
                        "Idempotency key '%s' was already used for different content (checksum %s vs %s)"
                                .formatted(key, expectedSha256, actual));
            }
            if (declaredSha256 != null && actual != null && !declaredSha256.equalsIgnoreCase(actual)) {
                throw new ChecksumMismatchException(
                        "Declared SHA-256 %s does not match computed %s"
                                .formatted(declaredSha256, actual));
            }
        }
    }

    private void validateCompatibility(ServiceType curService, String curVer, String curFile,
            String key, ServiceType reqService, String reqVer, String reqFile) {
        if (curService != reqService || !Objects.equals(curVer, reqVer)) {
            throw new IllegalArgumentException(
                    "Idempotency key '%s' was already used for a different artifact (%s %s vs %s %s)"
                            .formatted(key, curService, curVer, reqService, reqVer));
        }
        if (curFile != null && reqFile != null && !curFile.equals(reqFile)) {
            throw new IllegalArgumentException(
                    "Idempotency key '%s' was already used for a different file name (%s vs %s)"
                            .formatted(key, curFile, reqFile));
        }
    }

    private String keyHash(String key) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private IdempotencyRecord loadRecordFromDisk(String key) {
        try {
            Path root = Path.of(properties.getBasePath()).resolve(IDEMPOTENCY_DIR);
            Path target = root.resolve(keyHash(key) + ".json");
            if (Files.isRegularFile(target)) {
                IdempotencyRecord rec = objectMapper.readValue(target.toFile(), IdempotencyRecord.class);
                if (rec != null && Objects.equals(rec.idempotencyKey(), key)) {
                    return rec;
                }
            }
        } catch (Exception e) {
            log.debug("Could not load idempotency record for key {} from disk: {}", key, e.getMessage());
        }
        return null;
    }

    void persistRecord(IdempotencyRecord record) {
        try {
            Path root = Path.of(properties.getBasePath()).resolve(IDEMPOTENCY_DIR);
            Files.createDirectories(root);
            Path target = root.resolve(keyHash(record.idempotencyKey()) + ".json");
            Path temp = Files.createTempFile(root, ".idemp-", ".tmp");
            Files.writeString(temp, objectMapper.writeValueAsString(record));
            Files.move(temp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.error("Failed to persist idempotency record for key {}: {}", record.idempotencyKey(), e.getMessage(),
                    e);
            throw new StorageException("Failed to persist idempotency record for key: " + record.idempotencyKey(), e);
        }
    }

    private void loadPersistedRecords() {
        try {
            Path root = Path.of(properties.getBasePath()).resolve(IDEMPOTENCY_DIR);
            if (!Files.isDirectory(root))
                return;
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(file -> {
                    try {
                        IdempotencyRecord rec = objectMapper.readValue(file.toFile(), IdempotencyRecord.class);
                        if (rec != null && rec.idempotencyKey() != null) {
                            completed.put(rec.idempotencyKey(), rec);
                        }
                    } catch (Exception e) {
                        log.debug("Skipping invalid idempotency file {}: {}", file, e.getMessage());
                    }
                });
            }
            log.info("Loaded {} persisted idempotency records", completed.size());
        } catch (Exception e) {
            log.warn("Could not load persisted idempotency records: {}", e.getMessage());
        }
    }
}
