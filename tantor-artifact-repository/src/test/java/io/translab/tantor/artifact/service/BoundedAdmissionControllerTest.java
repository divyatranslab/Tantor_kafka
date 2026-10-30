package io.translab.tantor.artifact.service;

import io.translab.tantor.artifact.exception.UploadLimitExceededException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class BoundedAdmissionControllerTest {

    @Test
    void immediateAdmissionWhenCapacityAvailable() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 2, 5, Duration.ofSeconds(5));

        assertThat(controller.getActiveCount()).isEqualTo(0);
        assertThat(controller.getQueuedCount()).isEqualTo(0);

        try (BoundedAdmissionController.Lease lease1 = controller.acquire()) {
            assertThat(controller.getActiveCount()).isEqualTo(1);
            assertThat(controller.getQueuedCount()).isEqualTo(0);

            try (BoundedAdmissionController.Lease lease2 = controller.acquire()) {
                assertThat(controller.getActiveCount()).isEqualTo(2);
                assertThat(controller.getQueuedCount()).isEqualTo(0);
            }

            assertThat(controller.getActiveCount()).isEqualTo(1);
        }

        assertThat(controller.getActiveCount()).isEqualTo(0);
        assertThat(controller.getTotalAdmitted()).isEqualTo(2);
    }

    @Test
    void waitingWhenSlotsOccupiedAndQueueCapacityRemains() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 2, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease1 = controller.acquire();
        assertThat(controller.getActiveCount()).isEqualTo(1);

        CountDownLatch waitingStarted = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> future = executor.submit(() -> {
                waitingStarted.countDown();
                try (BoundedAdmissionController.Lease lease2 = controller.acquire()) {
                    waiterFinished.countDown();
                }
            });

            assertThat(waitingStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // Give time to enter the queue
            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                Thread.sleep(10);
            }
            assertThat(controller.getQueuedCount()).isEqualTo(1);
            assertThat(controller.getActiveCount()).isEqualTo(1);

            // Releasing lease1 should admit the waiter
            lease1.close();
            assertThat(waiterFinished.await(5, TimeUnit.SECONDS)).isTrue();
            future.get(5, TimeUnit.SECONDS);

            assertThat(controller.getActiveCount()).isEqualTo(0);
            assertThat(controller.getQueuedCount()).isEqualTo(0);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }

    @Test
    void fifoAdmissionOrderUnderContention() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 5, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease initialLease = controller.acquire();
        List<Integer> admissionOrder = Collections.synchronizedList(new ArrayList<>());
        int waitersCount = 3;
        CountDownLatch allQueued = new CountDownLatch(waitersCount);
        ExecutorService executor = Executors.newFixedThreadPool(waitersCount);

        try {
            for (int i = 0; i < waitersCount; i++) {
                final int id = i;
                executor.submit(() -> {
                    allQueued.countDown();
                    try (BoundedAdmissionController.Lease lease = controller.acquire()) {
                        admissionOrder.add(id);
                    }
                });
                // Small sleep to ensure deterministic arrival order at the gate
                Thread.sleep(30);
            }

            assertThat(allQueued.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() < waitersCount; i++) {
                Thread.sleep(10);
            }
            assertThat(controller.getQueuedCount()).isEqualTo(waitersCount);

            // Release initial lease to start the cascade
            initialLease.close();

            // Wait for all waiters to complete
            for (int i = 0; i < 50 && admissionOrder.size() < waitersCount; i++) {
                Thread.sleep(20);
            }

            assertThat(admissionOrder).containsExactly(0, 1, 2);
        } finally {
            initialLease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void immediateRejectionWhenQueueIsFull() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 1, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease1 = controller.acquire();
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            // Fill the queue capacity (maxQueued = 1)
            executor.submit(() -> {
                try (BoundedAdmissionController.Lease l = controller.acquire()) {
                    // hold slot
                }
            });

            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}
            }
            assertThat(controller.getQueuedCount()).isEqualTo(1);

            // 3rd request arrives when active=1 and queued=1 -> must be immediately rejected with queue full
            assertThatThrownBy(controller::acquire)
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("queue is full; try again shortly");

            assertThat(controller.getTotalRejectedQueueFull()).isGreaterThanOrEqualTo(1);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }

    @Test
    void queueTimeoutAndWaiterRemoval() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 2, Duration.ofMillis(80));

        BoundedAdmissionController.Lease lease = controller.acquire();
        try {
            assertThatThrownBy(controller::acquire)
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("queue wait timeout exceeded");

            assertThat(controller.getQueuedCount()).isEqualTo(0);
            assertThat(controller.getActiveCount()).isEqualTo(1);
            assertThat(controller.getTotalTimeouts()).isEqualTo(1);
        } finally {
            lease.close();
        }

        // After releasing, active count is 0 and next acquisition succeeds
        assertThat(controller.getActiveCount()).isEqualTo(0);
        try (BoundedAdmissionController.Lease next = controller.acquire()) {
            assertThat(controller.getActiveCount()).isEqualTo(1);
        }
    }

    @Test
    void timedOutWaiterNeverAcquiresSlotLater() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 2, Duration.ofMillis(60));

        BoundedAdmissionController.Lease lease = controller.acquire();
        AtomicBoolean waiterAcquired = new AtomicBoolean(false);
        CountDownLatch waiterDone = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> {
                try (BoundedAdmissionController.Lease l = controller.acquire()) {
                    waiterAcquired.set(true);
                } catch (UploadLimitExceededException e) {
                    // expected timeout
                } finally {
                    waiterDone.countDown();
                }
            });

            assertThat(waiterDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(waiterAcquired.get()).isFalse();
            assertThat(controller.getQueuedCount()).isEqualTo(0);

            // Release initial lease
            lease.close();

            // Ensure slot is free and not stolen by the timed out waiter
            assertThat(controller.getActiveCount()).isEqualTo(0);
            try (BoundedAdmissionController.Lease l = controller.acquire()) {
                assertThat(controller.getActiveCount()).isEqualTo(1);
            }
            assertThat(waiterAcquired.get()).isFalse();
        } finally {
            lease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptionAndWaiterRemoval() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 2, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease = controller.acquire();
        CountDownLatch inQueue = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean flagPreserved = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<?> future = executor.submit(() -> {
                inQueue.countDown();
                try {
                    controller.acquire();
                } catch (UploadLimitExceededException e) {
                    if (e.getMessage().contains("wait interrupted") && Thread.currentThread().isInterrupted()) {
                        flagPreserved.set(true);
                    }
                } finally {
                    interrupted.countDown();
                }
            });

            assertThat(inQueue.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                Thread.sleep(10);
            }

            future.cancel(true);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(flagPreserved.get()).isTrue();
            assertThat(controller.getQueuedCount()).isEqualTo(0);
            assertThat(controller.getActiveCount()).isEqualTo(1);
        } finally {
            lease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedLeaseClosureDoesNotReleaseCapacityTwice() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 2, 2, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease = controller.acquire();
        assertThat(controller.getActiveCount()).isEqualTo(1);

        lease.close();
        assertThat(controller.getActiveCount()).isEqualTo(0);

        // Repeated close must be a no-op and never decrement activeCount below 0
        lease.close();
        lease.close();
        assertThat(controller.getActiveCount()).isEqualTo(0);
    }

    @Test
    void zeroWaitingCapacityRejectsImmediatelyWhenBusy() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "zero-queue", 1, 0, Duration.ofSeconds(5));

        // When free, acquires immediately
        try (BoundedAdmissionController.Lease lease = controller.acquire()) {
            assertThat(controller.getActiveCount()).isEqualTo(1);

            // When busy and maxQueued=0, rejects immediately
            assertThatThrownBy(controller::acquire)
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("queue is full");
        }

        assertThat(controller.getActiveCount()).isEqualTo(0);
    }

    @Test
    void controllerShutdownWakesWaitersCleanly() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "shutdown-test", 1, 3, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease = controller.acquire();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterRejected = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            executor.submit(() -> {
                waiterStarted.countDown();
                try {
                    controller.acquire();
                } catch (UploadLimitExceededException e) {
                    if (e.getMessage().contains("is shutting down")) {
                        waiterRejected.countDown();
                    }
                }
            });

            assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                Thread.sleep(10);
            }

            controller.shutdown();
            assertThat(waiterRejected.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(controller.getQueuedCount()).isEqualTo(0);

            // Subsequent acquire fails immediately
            assertThatThrownBy(controller::acquire)
                    .isInstanceOf(UploadLimitExceededException.class)
                    .hasMessageContaining("is shutting down");
        } finally {
            lease.close();
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentSubmissionsNeverExceedConfiguredLimits() throws Exception {
        int maxConcurrent = 3;
        int maxQueued = 5;
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "concurrent", maxConcurrent, maxQueued, Duration.ofMillis(300));

        int totalThreads = 15;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalThreads);
        AtomicInteger maxObservedActive = new AtomicInteger(0);
        AtomicInteger maxObservedQueued = new AtomicInteger(0);
        AtomicInteger successfulAdmissions = new AtomicInteger(0);

        try {
            for (int i = 0; i < totalThreads; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        try (BoundedAdmissionController.Lease lease = controller.acquire()) {
                            successfulAdmissions.incrementAndGet();
                            int active = controller.getActiveCount();
                            int queued = controller.getQueuedCount();
                            maxObservedActive.accumulateAndGet(active, Math::max);
                            maxObservedQueued.accumulateAndGet(queued, Math::max);
                            Thread.sleep(20);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();

            assertThat(maxObservedActive.get()).isLessThanOrEqualTo(maxConcurrent);
            assertThat(maxObservedQueued.get()).isLessThanOrEqualTo(maxQueued);
            assertThat(controller.getActiveCount()).isEqualTo(0);
            assertThat(controller.getQueuedCount()).isEqualTo(0);
            assertThat(successfulAdmissions.get()).isGreaterThanOrEqualTo(maxConcurrent);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void independentControllersDoNotInterfere() {
        BoundedAdmissionController controller1 = new BoundedAdmissionController("c1", 1, 1, Duration.ofSeconds(1));
        BoundedAdmissionController controller2 = new BoundedAdmissionController("c2", 1, 1, Duration.ofSeconds(1));

        try (BoundedAdmissionController.Lease l1 = controller1.acquire()) {
            assertThat(controller1.getActiveCount()).isEqualTo(1);
            assertThat(controller2.getActiveCount()).isEqualTo(0);

            try (BoundedAdmissionController.Lease l2 = controller2.acquire()) {
                assertThat(controller1.getActiveCount()).isEqualTo(1);
                assertThat(controller2.getActiveCount()).isEqualTo(1);
            }
        }
    }

    @Test
    void timeoutVersusReleaseRaceHandledDeterministically() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "race-timeout", 1, 2, Duration.ofMillis(50));

        BoundedAdmissionController.Lease lease1 = controller.acquire();
        CountDownLatch waiterQueued = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);
        AtomicBoolean waiterAcquired = new AtomicBoolean(false);
        AtomicBoolean waiterTimedOut = new AtomicBoolean(false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            executor.submit(() -> {
                waiterQueued.countDown();
                try (BoundedAdmissionController.Lease l = controller.acquire()) {
                    waiterAcquired.set(true);
                } catch (UploadLimitExceededException e) {
                    if (e.getMessage().contains("timeout exceeded")) {
                        waiterTimedOut.set(true);
                    }
                } finally {
                    waiterDone.countDown();
                }
            });

            assertThat(waiterQueued.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                Thread.sleep(5);
            }

            // Close lease around the expiration time (approx 45ms)
            Thread.sleep(45);
            lease1.close();

            assertThat(waiterDone.await(5, TimeUnit.SECONDS)).isTrue();
            // Exactly one of waiterAcquired or waiterTimedOut must be true
            assertThat(waiterAcquired.get() ^ waiterTimedOut.get()).isTrue();

            // In both cases, active count and queued count must be 0 and capacity not leaked
            assertThat(controller.getActiveCount()).isEqualTo(0);
            assertThat(controller.getQueuedCount()).isEqualTo(0);

            // A new lease can be acquired immediately
            try (BoundedAdmissionController.Lease newLease = controller.acquire()) {
                assertThat(controller.getActiveCount()).isEqualTo(1);
            }
            assertThat(controller.getActiveCount()).isEqualTo(0);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptionVersusReleaseRaceHandledDeterministically() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "race-interrupt", 1, 2, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease1 = controller.acquire();
        CountDownLatch waiterQueued = new CountDownLatch(1);
        CountDownLatch waiterDone = new CountDownLatch(1);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = executor.submit(() -> {
                waiterQueued.countDown();
                try (BoundedAdmissionController.Lease l = controller.acquire()) {
                    // hold slot
                } catch (UploadLimitExceededException ignored) {
                } finally {
                    waiterDone.countDown();
                }
            });

            assertThat(waiterQueued.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                Thread.sleep(5);
            }

            // Interrupt waiter and release lease1 concurrently
            future.cancel(true);
            lease1.close();

            assertThat(waiterDone.await(5, TimeUnit.SECONDS)).isTrue();
            // In either outcome, the controller state must be consistent
            assertThat(controller.getActiveCount()).isEqualTo(0);
            assertThat(controller.getQueuedCount()).isEqualTo(0);

            // Capacity must not be lost: next acquire succeeds
            try (BoundedAdmissionController.Lease newLease = controller.acquire()) {
                assertThat(controller.getActiveCount()).isEqualTo(1);
            }
            assertThat(controller.getActiveCount()).isEqualTo(0);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }

    @Test
    void shutdownVersusHandoffRaceHandledDeterministically() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "race-shutdown", 1, 3, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease1 = controller.acquire();
        CountDownLatch waiterQueued = new CountDownLatch(2);
        CountDownLatch waitersFinished = new CountDownLatch(2);

        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 2; i++) {
                executor.submit(() -> {
                    waiterQueued.countDown();
                    try (BoundedAdmissionController.Lease l = controller.acquire()) {
                        // If admitted
                    } catch (UploadLimitExceededException ignored) {
                    } finally {
                        waitersFinished.countDown();
                    }
                });
            }

            assertThat(waiterQueued.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() < 2; i++) {
                Thread.sleep(5);
            }

            // Concurrently close lease and shutdown controller
            Future<?> fClose = executor.submit(lease1::close);
            Future<?> fShutdown = executor.submit(controller::shutdown);

            fClose.get(5, TimeUnit.SECONDS);
            fShutdown.get(5, TimeUnit.SECONDS);

            assertThat(waitersFinished.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(controller.isShutdown()).isTrue();
            assertThat(controller.getActiveCount()).isLessThanOrEqualTo(1);
            assertThat(controller.getActiveCount()).isGreaterThanOrEqualTo(0);
            assertThat(controller.getQueuedCount()).isEqualTo(0);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }

    @Test
    void repeatedShutdownIsIdempotent() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 1, 2, Duration.ofSeconds(5));

        assertThat(controller.isShutdown()).isFalse();
        controller.shutdown();
        assertThat(controller.isShutdown()).isTrue();

        // Repeated calls must be safe
        controller.shutdown();
        controller.close();
        assertThat(controller.isShutdown()).isTrue();

        assertThatThrownBy(controller::acquire)
                .isInstanceOf(UploadLimitExceededException.class)
                .hasMessageContaining("admission controller is shutting down");
    }

    @Test
    void leaseClosedAfterShutdownDecrementsActiveCountCleanly() {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "test", 2, 2, Duration.ofSeconds(5));

        BoundedAdmissionController.Lease lease1 = controller.acquire();
        BoundedAdmissionController.Lease lease2 = controller.acquire();
        assertThat(controller.getActiveCount()).isEqualTo(2);

        controller.shutdown();
        assertThat(controller.isShutdown()).isTrue();
        assertThat(controller.getActiveCount()).isEqualTo(2);

        // Closing leases after shutdown must not fail and must decrement active count to 0
        lease1.close();
        assertThat(controller.getActiveCount()).isEqualTo(1);

        lease2.close();
        assertThat(controller.getActiveCount()).isEqualTo(0);

        // Repeated close still safe
        lease1.close();
        lease2.close();
        assertThat(controller.getActiveCount()).isEqualTo(0);
    }

    @Test
    void averageWaitMetricIsRegisteredAndReportsMilliseconds() throws Exception {
        BoundedAdmissionController controller = new BoundedAdmissionController(
                "metric-test", 1, 2, Duration.ofSeconds(5));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        controller.registerMetrics(registry);

        Gauge waitGauge = registry.find("tantor.artifact.admission.wait.average.ms")
                .tag("controller", "metric-test")
                .gauge();

        assertThat(waitGauge).isNotNull();
        assertThat(waitGauge.getId().getBaseUnit()).isEqualTo("milliseconds");
        assertThat(waitGauge.value()).isEqualTo(0.0);
        assertThat(controller.getAverageWaitMs()).isEqualTo(0.0);

        // Verify other existing metrics are also properly registered
        assertThat(registry.find("tantor.artifact.admission.active").tag("controller", "metric-test").gauge()).isNotNull();
        assertThat(registry.find("tantor.artifact.admission.queued").tag("controller", "metric-test").gauge()).isNotNull();
        assertThat(registry.find("tantor.artifact.admission.admitted").tag("controller", "metric-test").functionCounter()).isNotNull();
        assertThat(registry.find("tantor.artifact.admission.rejected").tag("controller", "metric-test").tag("reason", "queue_full").functionCounter()).isNotNull();
        assertThat(registry.find("tantor.artifact.admission.rejected").tag("controller", "metric-test").tag("reason", "timeout").functionCounter()).isNotNull();

        // Repeated registration should not throw exception or cause duplicate registration failure
        controller.registerMetrics(registry);

        // Occupy the single available slot
        BoundedAdmissionController.Lease lease1 = controller.acquire();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);

        try {
            Future<?> future = executor.submit(() -> {
                waiterStarted.countDown();
                try (BoundedAdmissionController.Lease lease2 = controller.acquire()) {
                    waiterFinished.countDown();
                }
            });

            assertThat(waiterStarted.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 50 && controller.getQueuedCount() == 0; i++) {
                Thread.sleep(10);
            }
            assertThat(controller.getQueuedCount()).isEqualTo(1);

            // Wait a measurable duration while queued
            Thread.sleep(25);

            // Release lease1 so the queued waiter is admitted
            lease1.close();

            assertThat(waiterFinished.await(5, TimeUnit.SECONDS)).isTrue();
            future.get(5, TimeUnit.SECONDS);

            // Wait average ms should now be recorded and > 0.0
            assertThat(waitGauge.value()).isGreaterThan(0.0);
            assertThat(controller.getAverageWaitMs()).isEqualTo(waitGauge.value());
            assertThat(controller.getAverageWaitNanos()).isGreaterThan(0L);
        } finally {
            lease1.close();
            executor.shutdownNow();
        }
    }
}
