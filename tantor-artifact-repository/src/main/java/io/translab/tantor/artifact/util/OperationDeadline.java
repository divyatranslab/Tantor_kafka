package io.translab.tantor.artifact.util;

import io.translab.tantor.artifact.exception.UploadLimitExceededException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Tracks an end-to-end deadline across multi-stage operations (staging, validation,
 * scanning, commit, download, export) without resetting timeout independently at each stage.
 */
public final class OperationDeadline {

    private final Duration totalTimeout;
    private final long deadlineNanos;
    private final boolean unlimited;

    private OperationDeadline(Duration totalTimeout, boolean unlimited) {
        this.totalTimeout = totalTimeout;
        this.unlimited = unlimited;
        if (unlimited || totalTimeout == null) {
            this.deadlineNanos = Long.MAX_VALUE;
        } else {
            long calculated;
            try {
                calculated = Math.addExact(System.nanoTime(), totalTimeout.toNanos());
            } catch (ArithmeticException e) {
                calculated = Long.MAX_VALUE;
            }
            this.deadlineNanos = calculated;
        }
    }

    public static OperationDeadline start(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout must not be null");
        return new OperationDeadline(timeout, false);
    }

    public static OperationDeadline of(Duration timeout) {
        return start(timeout);
    }

    public static OperationDeadline unlimited() {
        return new OperationDeadline(null, true);
    }

    public boolean isUnlimited() {
        return unlimited;
    }

    public Duration getTotalTimeout() {
        return totalTimeout;
    }

    public boolean isExceeded() {
        if (unlimited) return false;
        return System.nanoTime() >= deadlineNanos;
    }

    public long remainingMillis() {
        if (unlimited) return Long.MAX_VALUE;
        long diffNanos = deadlineNanos - System.nanoTime();
        if (diffNanos <= 0) return 0L;
        return TimeUnit.NANOSECONDS.toMillis(diffNanos);
    }

    public Duration remainingDuration() {
        return Duration.ofMillis(remainingMillis());
    }

    public void checkNotExceeded(String stage) {
        if (!unlimited && isExceeded()) {
            throw new io.translab.tantor.artifact.exception.OperationTimeoutException(
                    "Operation timeout exceeded during " + stage + " (total timeout: " + (totalTimeout != null ? totalTimeout.toSeconds() : 0) + "s)");
        }
    }

    public long remainingMillisOrThrow(String stage) {
        checkNotExceeded(stage);
        long remaining = remainingMillis();
        if (remaining <= 0) {
            throw new io.translab.tantor.artifact.exception.OperationTimeoutException(
                    "Operation timeout exceeded during " + stage + " (total timeout: " + (totalTimeout != null ? totalTimeout.toSeconds() : 0) + "s)");
        }
        return remaining;
    }
}
