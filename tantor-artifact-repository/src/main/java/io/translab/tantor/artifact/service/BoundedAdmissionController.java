package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reusable, thread-safe bounded admission control gate providing strict FIFO queuing.
 *
 * <p>Invariants:
 * <ul>
 *   <li>{@code activeCount <= maxConcurrent}</li>
 *   <li>{@code queuedCount <= maxQueued}</li>
 *   <li>{@code activeCount} and {@code queuedCount} are never negative</li>
 *   <li>Capacity is released exactly once per acquired lease</li>
 *   <li>Strict FIFO: newly arriving threads cannot bypass existing eligible waiters</li>
 *   <li>Timed-out or cancelled waiters are safely removed and cannot claim slots later</li>
 *   <li>The controller lock is never held during external task execution</li>
 * </ul>
 */
public class BoundedAdmissionController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedAdmissionController.class);

    private final String name;
    private final int maxConcurrent;
    private final int maxQueued;
    private final long waitTimeoutNanos;

    private final ReentrantLock lock = new ReentrantLock(true);
    private final ArrayDeque<Waiter> waiters = new ArrayDeque<>();

    private int activeCount = 0;
    private boolean shutdown = false;

    // Observability metrics
    private final AtomicLong totalAdmitted = new AtomicLong(0);
    private final AtomicLong totalRejectedQueueFull = new AtomicLong(0);
    private final AtomicLong totalTimeouts = new AtomicLong(0);
    private final AtomicLong totalWaitNanos = new AtomicLong(0);
    private final AtomicLong totalWaitCount = new AtomicLong(0);

    // Warning log throttling under sustained overload
    private static final long LOG_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final AtomicLong lastQueueFullLogNanos = new AtomicLong(0);
    private final AtomicLong suppressedQueueFullLogs = new AtomicLong(0);
    private final AtomicLong lastTimeoutLogNanos = new AtomicLong(0);
    private final AtomicLong suppressedTimeoutLogs = new AtomicLong(0);

    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Waiter {
        final Condition condition;
        boolean granted = false;
        boolean cancelled = false;

        Waiter(ReentrantLock lock) {
            this.condition = lock.newCondition();
        }
    }

    public BoundedAdmissionController(String name, int maxConcurrent, int maxQueued, Duration waitTimeout) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        if (maxQueued < 0) {
            throw new IllegalArgumentException("maxQueued must be >= 0");
        }
        Objects.requireNonNull(waitTimeout, "waitTimeout must not be null");
        if (waitTimeout.isNegative() || waitTimeout.isZero()) {
            throw new IllegalArgumentException("waitTimeout must be positive");
        }

        this.name = name;
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
        this.waitTimeoutNanos = waitTimeout.toNanos();
    }

    /**
     * Acquires admission capacity, waiting in strict FIFO order if worker slots are currently full.
     *
     * @return a {@link Lease} that releases the acquired capacity upon {@link Lease#close()}
     * @throws UploadLimitExceededException if the queue is full, the wait timeout expires, or the controller is shutting down
     */
    public Lease acquire() {
        long startNanos = System.nanoTime();
        Waiter myWaiter = null;
        lock.lock();
        try {
            if (shutdown) {
                throw new UploadLimitExceededException(name + " admission controller is shutting down");
            }

            // Fast path: slot available and no waiting threads in front of us
            if (activeCount < maxConcurrent && waiters.isEmpty()) {
                activeCount++;
                totalAdmitted.incrementAndGet();
                return new LeaseImpl();
            }

            // Queue capacity check: reject immediately if queue capacity is exceeded
            if (waiters.size() >= maxQueued) {
                totalRejectedQueueFull.incrementAndGet();
                logQueueFullWarning(activeCount, waiters.size(), maxQueued);
                throw new UploadLimitExceededException(name + " queue is full; try again shortly");
            }

            // Enqueue waiter at tail to preserve strict FIFO ordering
            myWaiter = new Waiter(lock);
            waiters.addLast(myWaiter);
            long nanosLeft = waitTimeoutNanos;

            while (!myWaiter.granted && !myWaiter.cancelled && !shutdown) {
                if (nanosLeft <= 0) {
                    myWaiter.cancelled = true;
                    waiters.remove(myWaiter);
                    totalTimeouts.incrementAndGet();
                    logTimeoutWarning(startNanos, waiters.size());
                    throw new UploadLimitExceededException(name + " queue wait timeout exceeded; try again shortly");
                }
                try {
                    nanosLeft = myWaiter.condition.awaitNanos(nanosLeft);
                } catch (InterruptedException e) {
                    myWaiter.cancelled = true;
                    waiters.remove(myWaiter);
                    Thread.currentThread().interrupt();
                    log.warn("{} admission wait interrupted", name);
                    throw new UploadLimitExceededException(name + " wait interrupted");
                }
            }

            if (shutdown) {
                myWaiter.cancelled = true;
                waiters.remove(myWaiter);
                throw new UploadLimitExceededException(name + " admission controller is shutting down");
            }

            // Successfully admitted through the queue
            long waitDuration = System.nanoTime() - startNanos;
            totalWaitNanos.addAndGet(waitDuration);
            totalWaitCount.incrementAndGet();
            totalAdmitted.incrementAndGet();
            return new LeaseImpl();

        } finally {
            // Handle race conditions where slot was granted right before waiter timeout, interruption, or shutdown
            if (myWaiter != null && myWaiter.granted && myWaiter.cancelled) {
                handoffOrReleaseSlot();
            }
            lock.unlock();
        }
    }

    private void logQueueFullWarning(int active, int queued, int maxQueuedLimit) {
        long now = System.nanoTime();
        long last = lastQueueFullLogNanos.get();
        if (now - last > LOG_THROTTLE_NANOS && lastQueueFullLogNanos.compareAndSet(last, now)) {
            long suppressed = suppressedQueueFullLogs.getAndSet(0);
            if (suppressed > 0) {
                log.warn("{} admission rejected: queue is full (active={}, queued={}, maxQueued={}) [{} similar rejections suppressed]",
                        name, active, queued, maxQueuedLimit, suppressed);
            } else {
                log.warn("{} admission rejected: queue is full (active={}, queued={}, maxQueued={})",
                        name, active, queued, maxQueuedLimit);
            }
        } else {
            suppressedQueueFullLogs.incrementAndGet();
            log.debug("{} admission rejected: queue is full (active={}, queued={}, maxQueued={})",
                    name, active, queued, maxQueuedLimit);
        }
    }

    private void logTimeoutWarning(long startNanos, int queued) {
        long now = System.nanoTime();
        long last = lastTimeoutLogNanos.get();
        long waitDurationMs = TimeUnit.NANOSECONDS.toMillis(now - startNanos);
        if (now - last > LOG_THROTTLE_NANOS && lastTimeoutLogNanos.compareAndSet(last, now)) {
            long suppressed = suppressedTimeoutLogs.getAndSet(0);
            if (suppressed > 0) {
                log.warn("{} admission rejected: queue wait timeout exceeded (waitDurationMs={}, queued={}) [{} similar timeouts suppressed]",
                        name, waitDurationMs, queued, suppressed);
            } else {
                log.warn("{} admission rejected: queue wait timeout exceeded (waitDurationMs={}, queued={})",
                        name, waitDurationMs, queued);
            }
        } else {
            suppressedTimeoutLogs.incrementAndGet();
            log.debug("{} admission rejected: queue wait timeout exceeded (waitDurationMs={}, queued={})",
                    name, waitDurationMs, queued);
        }
    }

    private void handoffOrReleaseSlot() {
        while (!waiters.isEmpty()) {
            Waiter next = waiters.pollFirst();
            if (!next.cancelled) {
                next.granted = true;
                next.condition.signal();
                return; // Direct handoff to head waiter; activeCount unchanged
            }
        }
        if (activeCount > 0) {
            activeCount--;
        }
    }

    private final class LeaseImpl implements Lease {
        private final AtomicBoolean closed = new AtomicBoolean(false);

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                lock.lock();
                try {
                    handoffOrReleaseSlot();
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    @Override
    public void close() {
        shutdown();
    }

    public void shutdown() {
        lock.lock();
        try {
            if (shutdown) {
                return;
            }
            shutdown = true;
            while (!waiters.isEmpty()) {
                Waiter w = waiters.pollFirst();
                w.cancelled = true;
                w.condition.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isShutdown() {
        lock.lock();
        try {
            return shutdown;
        } finally {
            lock.unlock();
        }
    }

    public String getName() {
        return name;
    }

    public int getMaxConcurrent() {
        return maxConcurrent;
    }

    public int getMaxQueued() {
        return maxQueued;
    }

    public Duration getWaitTimeout() {
        return Duration.ofNanos(waitTimeoutNanos);
    }

    public int getActiveCount() {
        lock.lock();
        try {
            return activeCount;
        } finally {
            lock.unlock();
        }
    }

    public int getQueuedCount() {
        lock.lock();
        try {
            return waiters.size();
        } finally {
            lock.unlock();
        }
    }

    public long getTotalAdmitted() {
        return totalAdmitted.get();
    }

    public long getTotalRejectedQueueFull() {
        return totalRejectedQueueFull.get();
    }

    public long getTotalTimeouts() {
        return totalTimeouts.get();
    }

    /**
     * Returns average wait duration in nanoseconds for requests that waited in queue and were admitted.
     */
    public long getAverageWaitNanos() {
        long count = totalWaitCount.get();
        return count == 0 ? 0 : totalWaitNanos.get() / count;
    }

    /**
     * Returns average wait duration in milliseconds for requests that waited in queue and were admitted.
     */
    public double getAverageWaitMs() {
        long count = totalWaitCount.get();
        return count == 0 ? 0.0 : ((double) totalWaitNanos.get()) / (count * 1_000_000.0);
    }

    public void registerMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        if (registry == null) return;
        io.micrometer.core.instrument.Gauge.builder("tantor.artifact.admission.active", this, BoundedAdmissionController::getActiveCount)
                .description("Current number of active operations executing in worker slots")
                .tag("controller", name).register(registry);
        io.micrometer.core.instrument.Gauge.builder("tantor.artifact.admission.queued", this, BoundedAdmissionController::getQueuedCount)
                .description("Current number of operations waiting in the bounded admission queue")
                .tag("controller", name).register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("tantor.artifact.admission.admitted", this, BoundedAdmissionController::getTotalAdmitted)
                .description("Total number of operations admitted through this controller")
                .tag("controller", name).register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("tantor.artifact.admission.rejected", this, BoundedAdmissionController::getTotalRejectedQueueFull)
                .description("Total number of operations rejected due to queue saturation")
                .tag("controller", name).tag("reason", "queue_full").register(registry);
        io.micrometer.core.instrument.FunctionCounter.builder("tantor.artifact.admission.rejected", this, BoundedAdmissionController::getTotalTimeouts)
                .description("Total number of operations rejected due to queue wait timeout")
                .tag("controller", name).tag("reason", "timeout").register(registry);
        io.micrometer.core.instrument.Gauge.builder("tantor.artifact.admission.wait.average.ms", this, BoundedAdmissionController::getAverageWaitMs)
                .description("Average wait time in milliseconds for queued requests that were admitted")
                .baseUnit("milliseconds")
                .tag("controller", name).register(registry);
    }
}
