package io.translab.tantor.server.service;

import org.apache.kafka.common.KafkaFuture;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class KafkaRefreshDeadline {
    private KafkaRefreshDeadline() {}

    static long afterMillis(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
    }

    static int remainingMillis(long deadlineNanos) throws TimeoutException {
        long remaining = deadlineNanos - System.nanoTime();
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remaining);
        if (remainingMillis <= 0) throw new TimeoutException("Kafka refresh deadline exceeded");
        return (int) Math.min(Integer.MAX_VALUE, remainingMillis);
    }

    /**
     * Bounds the caller wait using the shared monotonic deadline. A timeout does not cancel
     * the underlying Kafka request; callers must also configure Kafka Admin request timeouts.
     */
    static <T> T await(KafkaFuture<T> future, long deadlineNanos) throws Exception {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Kafka refresh deadline exceeded");
        return future.get(remaining, TimeUnit.NANOSECONDS);
    }
}
