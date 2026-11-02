package io.translab.tantor.artifact.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.translab.tantor.artifact.config.StorageProperties;
import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Enforces a global aggregate temporary-storage budget across concurrent uploads,
 * bundle imports, and archive extractions, preventing disk volume exhaustion.
 */
@Component
public class TemporaryStorageBudget {

    private static final Logger log = LoggerFactory.getLogger(TemporaryStorageBudget.class);

    private final StorageProperties properties;
    private final MeterRegistry meterRegistry;
    private final java.util.concurrent.locks.ReentrantLock lock = new java.util.concurrent.locks.ReentrantLock();
    private long reservedBytes = 0;
    private final AtomicLong rejectedReservations = new AtomicLong(0);
    private final AtomicLong cleanupFailures = new AtomicLong(0);

    private Counter rejectedCounter;
    private Counter cleanupFailuresCounter;

    public TemporaryStorageBudget(StorageProperties properties) {
        this(properties, null);
    }

    @Autowired
    public TemporaryStorageBudget(StorageProperties properties,
                                  @Autowired(required = false) MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        if (meterRegistry != null) {
            Gauge.builder("tantor.artifact.storage.temp.reserved.bytes", this, TemporaryStorageBudget::getReservedBytes)
                    .description("Currently reserved temporary storage in bytes")
                    .baseUnit("bytes")
                    .register(meterRegistry);

            Gauge.builder("tantor.artifact.storage.temp.budget.bytes", properties, StorageProperties::getMaxAggregateTempBytes)
                    .description("Maximum aggregate temporary storage budget in bytes")
                    .baseUnit("bytes")
                    .register(meterRegistry);

            Gauge.builder("tantor.artifact.storage.temp.available.bytes", this, TemporaryStorageBudget::getAvailableBudgetBytes)
                    .description("Remaining available temporary storage budget in bytes")
                    .baseUnit("bytes")
                    .register(meterRegistry);

            rejectedCounter = Counter.builder("tantor.artifact.storage.temp.rejected.total")
                    .description("Total rejected temporary storage reservations due to budget exhaustion")
                    .register(meterRegistry);

            cleanupFailuresCounter = Counter.builder("tantor.artifact.storage.temp.cleanup.failures.total")
                    .description("Total temporary storage file cleanup failures")
                    .register(meterRegistry);
        }
    }

    public long getReservedBytes() {
        lock.lock();
        try {
            return reservedBytes;
        } finally {
            lock.unlock();
        }
    }

    public long getAvailableBudgetBytes() {
        lock.lock();
        try {
            long max = properties.getMaxAggregateTempBytes();
            return Math.max(0L, max - reservedBytes);
        } finally {
            lock.unlock();
        }
    }

    public long getRejectedCount() {
        return rejectedReservations.get();
    }

    public long getCleanupFailureCount() {
        return cleanupFailures.get();
    }

    public void recordCleanupFailure() {
        cleanupFailures.incrementAndGet();
        if (cleanupFailuresCounter != null) {
            cleanupFailuresCounter.increment();
        }
    }

    public void accountExistingTempFiles(Path root) {
        if (root == null || !Files.isDirectory(root)) return;
        lock.lock();
        try {
            long existingBytes = 0;
            try (Stream<Path> stream = Files.list(root)) {
                List<Path> tempFiles = stream
                        .filter(p -> p.getFileName().toString().startsWith(".upload-")
                                || p.getFileName().toString().startsWith("tantor-bundle-"))
                        .toList();
                for (Path p : tempFiles) {
                    try {
                        if (Files.isDirectory(p)) {
                            try (Stream<Path> walk = Files.walk(p)) {
                                long dirSize = walk.filter(Files::isRegularFile)
                                        .mapToLong(f -> {
                                            try { return Files.size(f); } catch (Exception ignored) { return 0L; }
                                        }).sum();
                                existingBytes = Math.addExact(existingBytes, dirSize);
                            }
                        } else {
                            existingBytes = Math.addExact(existingBytes, Files.size(p));
                        }
                    } catch (Exception ignored) {}
                }
            } catch (IOException e) {
                log.warn("Could not list temporary files for startup budget accounting: {}", e.getMessage());
            }
            if (existingBytes > 0) {
                reservedBytes = Math.min(properties.getMaxAggregateTempBytes(),
                        Math.addExact(reservedBytes, existingBytes));
                log.info("Accounted {} existing temporary staging bytes against budget on startup", existingBytes);
            }
        } finally {
            lock.unlock();
        }
    }

    public interface Reservation extends AutoCloseable {
        long getReservedBytes();
        void adjust(long newTotalBytes);
        @Override
        void close();
    }

    /**
     * Atomically reserve temporary storage bytes against both the aggregate budget
     * and the filesystem free-space allowance.
     */
    public Reservation reserve(long bytesToReserve, Path filesystemRoot) {
        if (bytesToReserve < 0) {
            throw new IllegalArgumentException("Reservation bytes cannot be negative");
        }
        long maxBudget = properties.getMaxAggregateTempBytes();
        if (maxBudget <= 0) {
            throw new IllegalArgumentException("Maximum aggregate temporary storage budget must be positive");
        }

        lock.lock();
        try {
            long newTotal;
            try {
                newTotal = Math.addExact(reservedBytes, bytesToReserve);
            } catch (ArithmeticException e) {
                rejectedReservations.incrementAndGet();
                if (rejectedCounter != null) rejectedCounter.increment();
                throw new UploadLimitExceededException("Temporary storage budget reservation would cause arithmetic overflow");
            }

            if (newTotal > maxBudget) {
                rejectedReservations.incrementAndGet();
                if (rejectedCounter != null) rejectedCounter.increment();
                log.warn("Temporary storage reservation rejected: requested {} bytes, currently reserved {} bytes, max budget {} bytes",
                        bytesToReserve, reservedBytes, maxBudget);
                throw new UploadLimitExceededException(
                        "Temporary storage budget exhausted; try again shortly (requested="
                                + bytesToReserve + " bytes, currentlyReserved=" + reservedBytes + " bytes)");
            }

            // Additional safeguard: verify filesystem free space of actual filesystemRoot
            if (filesystemRoot != null) {
                try {
                    long usable = Files.getFileStore(filesystemRoot).getUsableSpace();
                    long required = Math.addExact(properties.getMinimumFreeSpaceBytes(), bytesToReserve);
                    if (usable < required) {
                        rejectedReservations.incrementAndGet();
                        if (rejectedCounter != null) rejectedCounter.increment();
                        throw new UploadLimitExceededException("Insufficient repository disk space for temporary storage reservation");
                    }
                } catch (IOException e) {
                    log.warn("Could not check filesystem usable space for {}: {}", filesystemRoot, e.getMessage());
                }
            }

            reservedBytes = newTotal;
            return new BudgetReservation(bytesToReserve);
        } finally {
            lock.unlock();
        }
    }

    private final class BudgetReservation implements Reservation {
        private boolean closed = false;
        private long currentBytes;

        private BudgetReservation(long initialBytes) {
            this.currentBytes = initialBytes;
        }

        @Override
        public long getReservedBytes() {
            lock.lock();
            try {
                return currentBytes;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void adjust(long newTotalBytes) {
            if (newTotalBytes < 0) return;
            lock.lock();
            try {
                if (closed) return;
                long delta = newTotalBytes - currentBytes;
                if (delta == 0) return;

                long newReserved;
                try {
                    newReserved = Math.addExact(reservedBytes, delta);
                } catch (ArithmeticException e) {
                    rejectedReservations.incrementAndGet();
                    if (rejectedCounter != null) rejectedCounter.increment();
                    throw new UploadLimitExceededException("Temporary storage reservation adjustment would cause arithmetic overflow");
                }

                if (delta > 0 && newReserved > properties.getMaxAggregateTempBytes()) {
                    rejectedReservations.incrementAndGet();
                    if (rejectedCounter != null) rejectedCounter.increment();
                    throw new UploadLimitExceededException("Cannot increase temporary storage reservation; budget exhausted");
                }

                reservedBytes = Math.max(0L, newReserved);
                currentBytes = newTotalBytes;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                if (closed) return;
                closed = true;
                if (currentBytes > 0) {
                    reservedBytes = Math.max(0L, reservedBytes - currentBytes);
                    currentBytes = 0;
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
