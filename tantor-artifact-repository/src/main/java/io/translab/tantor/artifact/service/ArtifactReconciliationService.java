package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.domain.Artifact;
import io.translab.tantor.artifact.domain.ArtifactStatus;
import io.translab.tantor.artifact.repository.ArtifactJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.HashSet;
import java.util.Set;

/**
 * Reconciles filesystem and database inconsistencies across crashes, restarts,
 * failed transactions, and partial uploads.
 */
@Service
public class ArtifactReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactReconciliationService.class);
    private static final String PENDING_CLEANUP_DIR = ".pending-cleanups";

    private final ArtifactJpaRepository repository;
    private final StorageService storageService;
    private final StorageProperties properties;
    private final TemporaryStorageBudget storageBudget;

    private final Queue<Path> pendingCleanups = new ConcurrentLinkedQueue<>();

    public ArtifactReconciliationService(ArtifactJpaRepository repository,
            StorageService storageService,
            StorageProperties properties,
            TemporaryStorageBudget storageBudget) {
        this.repository = repository;
        this.storageService = storageService;
        this.properties = properties;
        this.storageBudget = storageBudget;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            reconcileAll();
        } catch (Exception e) {
            log.warn("Error during startup reconciliation: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${tantor.storage.reconciliation-interval:60000}", initialDelay = 60000)
    public void scheduledReconciliation() {
        try {
            retryPendingCleanups();
        } catch (Exception e) {
            log.warn("Error during scheduled pending cleanups retry: {}", e.getMessage());
        }
        try {
            reconcileStaleTempFiles();
        } catch (Exception e) {
            log.warn("Error during scheduled stale temp files reconciliation: {}", e.getMessage());
        }
        try {
            reconcileMissingBinaries();
        } catch (Exception e) {
            log.warn("Error during scheduled missing binaries reconciliation: {}", e.getMessage());
        }
    }

    /**
     * Executes complete reconciliation of stale temp files, missing binaries,
     * orphaned files, and retries pending cleanups.
     */
    public ReconciliationResult reconcileAll() {
        log.info("Starting artifact repository consistency reconciliation");
        int staleTempDeleted = reconcileStaleTempFiles();
        int missingBinariesDetected = reconcileMissingBinaries();
        int pendingRetried = retryPendingCleanups();
        log.info(
                "Reconciliation completed: {} stale temp files removed, {} missing binaries flagged, {} cleanups retried",
                staleTempDeleted, missingBinariesDetected, pendingRetried);
        return new ReconciliationResult(staleTempDeleted, missingBinariesDetected, pendingRetried);
    }

    public record ReconciliationResult(int staleTempDeleted, int missingBinariesDetected, int pendingCleanupsRetried) {
    }

    /**
     * Enqueue a file for deferred or retryable cleanup if immediate deletion fails.
     * Also journals the path to disk so cleanups survive process restarts.
     */
    public void enqueueForCleanup(Path path) {
        if (path == null)
            return;
        pendingCleanups.offer(path);
        persistPendingCleanup(path);
    }

    /**
     * Reconciles stale temporary files (`.upload-*`) in repository root older than
     * configured threshold.
     * Active uploads are strictly guarded against deletion.
     */
    public int reconcileStaleTempFiles() {
        Path root = storageService.artifactsRoot();
        if (root == null || !Files.isDirectory(root))
            return 0;

        Instant cutoff = Instant.now().minus(properties.getStaleFileThreshold());
        int cleaned = 0;

        try (Stream<Path> files = Files.list(root)) {
            List<Path> toDelete = files
                    .filter(p -> p.getFileName().toString().startsWith(".upload-")
                            || p.getFileName().toString().startsWith("tantor-bundle-"))
                    .filter(p -> !storageService.isActiveUpload(p))
                    .filter(p -> isOlderThan(p, cutoff))
                    .toList();

            for (Path tempFile : toDelete) {
                try {
                    if (Files.isDirectory(tempFile)) {
                        deleteRecursively(tempFile);
                    } else {
                        Files.deleteIfExists(tempFile);
                    }
                    cleaned++;
                    log.info("Reconciled stale temporary staging path: {}", tempFile);
                } catch (IOException e) {
                    log.warn("Failed to delete stale temp file {}: {}", tempFile, e.getMessage());
                    storageBudget.recordCleanupFailure();
                    enqueueForCleanup(tempFile);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list repository root for stale temp file reconciliation: {}", e.getMessage());
        }
        return cleaned;
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .toList();

            for (Path path : paths) {
                deletePath(path);
            }
        }
    }

    void deletePath(Path path) throws IOException {
        Files.deleteIfExists(path);
    }

    /**
     * Detects records marked AVAILABLE whose physical binaries are missing on disk,
     * and flags their status as CORRUPTED. Processed in bounded batches without
     * holding long-running database transactions.
     * <p>
     * If a paginated query fails, the failure is logged and the operation is
     * aborted
     * so it can be retried cleanly on the next scheduled reconciliation cycle
     * without
     * falling back to an unbounded query that could exhaust heap memory.
     */
    public int reconcileMissingBinaries() {
        int page = 0;
        int pageSize = 100;
        int corruptedCount = 0;
        Page<Artifact> slice = null;

        try {
            do {
                slice = repository.findByStatusAndAction(
                        ArtifactStatus.AVAILABLE, "UPLOAD", PageRequest.of(page, pageSize));
                if (slice == null || slice.isEmpty()) {
                    break;
                }
                for (Artifact a : slice.getContent()) {
                    corruptedCount += checkAndMarkCorrupted(a);
                }
                page++;
            } while (slice.hasNext());
        } catch (Exception e) {
            log.warn(
                    "Paginated query for missing binaries failed at page {}: {}. Will retry on next scheduled reconciliation cycle.",
                    page, e.getMessage(), e);
        }

        return corruptedCount;
    }

    private int checkAndMarkCorrupted(Artifact a) {
        try {
            String relPath = a.getRelativePath();
            if (relPath == null || !relPath.contains("/")) {
                log.warn("Artifact {} has malformed relative path: {}", a.getId(), relPath);
                markCorrupted(a);
                return 1;
            }
            String relDir = relPath.substring(0, relPath.lastIndexOf('/'));
            Path binary = storageService.resolveBinary(relDir, a.getFileName());
            if (!Files.isRegularFile(binary)) {
                log.warn("Reconciliation detected missing binary for AVAILABLE artifact {}: expected at {}",
                        a.getId(), binary);
                markCorrupted(a);
                return 1;
            }
        } catch (Exception e) {
            log.warn("Error verifying binary existence for artifact {}: {}", a.getId(), e.getMessage());
        }
        return 0;
    }

    private void markCorrupted(Artifact a) {
        try {
            a.setStatus(ArtifactStatus.CORRUPTED);
            repository.save(a);
        } catch (Exception e) {
            log.warn("Failed to mark artifact {} as CORRUPTED: {}", a.getId(), e.getMessage());
        }
    }

    /**
     * Retries any failed deletions recorded in the pending queue and on-disk
     * journal.
     */
    public int retryPendingCleanups() {
        int retried = 0;

        Set<Path> processedPath = new HashSet<>();
        // 1. Drain in-memory queue
        int size = pendingCleanups.size();
        for (int i = 0; i < size; i++) {
            Path path = pendingCleanups.poll();
            if (path == null)
                break;

            Path normalizedPath = path.toAbsolutePath().normalize();

            if (!processedPath.add(normalizedPath)) {
                continue;
            }
            try {
                if (Files.isDirectory(normalizedPath)) {
                    deleteRecursively(normalizedPath);
                    retried++;
                    log.info("Successfully cleaned up pending directory: {}", normalizedPath);
                } else if (Files.deleteIfExists(normalizedPath)) {
                    retried++;
                    log.info("Successfully cleaned up pending path: {}", normalizedPath);
                }
                removeJournalMarker(normalizedPath);
            } catch (IOException e) {
                log.warn("Retry cleanup failed for {}: {}", normalizedPath, e.getMessage(), e);
                storageBudget.recordCleanupFailure();
                pendingCleanups.offer(normalizedPath);
            }
        }

        // 2. Process durable on-disk journal
        Path root = storageService.artifactsRoot();
        if (root != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path journalDir = normalizedRoot.resolve(PENDING_CLEANUP_DIR);
            if (Files.isDirectory(journalDir)) {
                try (Stream<Path> stream = Files.list(journalDir)) {
                    List<Path> markers = stream.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
                    for (Path marker : markers) {
                        try {
                            String raw = Files.readString(marker).trim();
                            Path target = Path.of(raw).toAbsolutePath().normalize();
                            if (!target.startsWith(normalizedRoot)) {
                                log.warn("Ignoring invalid journaled path outside repository root: {}", target);
                                Files.deleteIfExists(marker);
                                continue;
                            }
                            if (!processedPath.add(target)) {
                                log.debug("Skipping journal entry already processed from queue: {}", target);
                                continue;
                            }
                            if (Files.isDirectory(target)) {
                                deleteRecursively(target);
                                retried++;
                                log.info("Successfully cleaned up journaled path: {}", target);
                            } else if (Files.deleteIfExists(target)) {
                                retried++;
                            }
                            Files.deleteIfExists(marker);
                        } catch (IOException e) {
                            log.warn("Failed to process cleanup journal marker {}: {}", marker, e.getMessage(), e);
                            storageBudget.recordCleanupFailure();
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to list pending cleanups journal: {}", e.getMessage(), e);
                }
            }
        }

        return retried;
    }

    private void persistPendingCleanup(Path path) {
        try {
            Path root = storageService.artifactsRoot();
            if (root == null)
                return;
            Path journalDir = root.resolve(PENDING_CLEANUP_DIR);
            Files.createDirectories(journalDir);
            String hash = hashPath(path);
            Path marker = journalDir.resolve(hash + ".json");
            Path temp = Files.createTempFile(journalDir, ".marker-", ".tmp");
            Files.writeString(temp, path.toAbsolutePath().normalize().toString());
            Files.move(temp, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            log.warn("Could not persist pending cleanup marker for {}: {}", path, e.getMessage());
        }
    }

    void removeJournalMarker(Path path) throws IOException {

        Path root = storageService.artifactsRoot();
        if (root == null) {
            return;
        }
        Path marker = root.toAbsolutePath().normalize().resolve(PENDING_CLEANUP_DIR).resolve(hashPath(path) + ".json");
        Files.deleteIfExists(marker);

    }

    private String hashPath(Path path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            return String.valueOf(path.hashCode());
        }
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            FileTime lastModified = Files.getLastModifiedTime(path);
            return lastModified.toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }
}
