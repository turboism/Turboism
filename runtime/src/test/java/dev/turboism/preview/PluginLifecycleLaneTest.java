package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLifecycleLaneTest {

    private static final PluginLifecyclePolicy POLICY = new PluginLifecyclePolicy(
        1,
        2,
        Duration.ofMillis(200),
        Duration.ofMillis(100),
        Duration.ofMillis(200),
        Duration.ofMillis(20),
        Duration.ofMillis(20)
    );

    @Test
    void succeededResultReturnsValue() {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        try {
            final PluginLifecycleLane.Invocation<String> invocation =
                lane.submit("p", "load", () -> "done");
            final PluginLifecycleLane.AwaitResult<String> result =
                lane.await(invocation, Duration.ofSeconds(2), null);
            assertEquals(PluginLifecycleLane.Outcome.SUCCEEDED, result.outcome);
            assertEquals("done", result.value);
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void timedOutTaskFencesAndWorkerDoneTracksActualExit() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean ran = new AtomicBoolean();
        try {
            final PluginLifecycleLane.Invocation<Void> invocation = lane.submit(
                "p",
                "load",
                () -> {
                    ran.set(true);
                    release.await(30, TimeUnit.SECONDS);
                    return null;
                }
            );
            final PluginLifecycleLane.AwaitResult<Void> result =
                lane.await(invocation, Duration.ofMillis(80), new PluginLifecycleLease("p"));
            assertEquals(PluginLifecycleLane.Outcome.TIMED_OUT, result.outcome);
            assertTrue(ran.get(), "timed-out task was running, not merely queued");
            // Interrupt is advisory: wait() is interruptible, but even while the worker is still
            // unwinding workerDone must only complete when it actually exits.
            release.countDown();
            invocation.workerDone.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }

    @Test
    void uninterruptibleWorkerKeepsWorkerDonePendingUntilReleased() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final CountDownLatch entered = new CountDownLatch(1);
        final AtomicBoolean release = new AtomicBoolean();
        try {
            final PluginLifecycleLane.Invocation<Void> invocation = lane.submit(
                "p",
                "load",
                () -> {
                    entered.countDown();
                    while (!release.get()) {
                        Thread.onSpinWait();
                    }
                    return null;
                }
            );
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            final PluginLifecycleLane.AwaitResult<Void> result =
                lane.await(invocation, Duration.ofMillis(60), new PluginLifecycleLease("p"));
            assertEquals(PluginLifecycleLane.Outcome.TIMED_OUT, result.outcome);
            assertFalse(
                invocation.workerDone.isDone(),
                "a cancelled/interrupted worker is not proof the plugin code stopped"
            );
            release.set(true);
            invocation.workerDone.get(5, TimeUnit.SECONDS);
        } finally {
            release.set(true);
            lane.shutdown();
        }
    }

    @Test
    void committedSectionWinsAgainstTimeout() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final PluginLifecycleLease lease = new PluginLifecycleLease("p");
        try {
            final PluginLifecycleLane.Invocation<String> invocation = lane.submit(
                "p",
                "load",
                () -> {
                    final String committed = lease.commit(() -> "committed");
                    // Finish after the caller's deadline: the commit already won, so the caller
                    // must observe SUCCEEDED rather than TIMED_OUT.
                    Thread.sleep(120);
                    return committed;
                }
            );
            final PluginLifecycleLane.AwaitResult<String> result =
                lane.await(invocation, Duration.ofMillis(40), lease);
            assertEquals(PluginLifecycleLane.Outcome.SUCCEEDED, result.outcome);
            assertEquals("committed", result.value);
            assertTrue(lease.isCommitted());
        } finally {
            lane.shutdown();
        }
    }

    @Test
    void saturatedQueueRejectsWithoutRunningAndWorkerDoneSettles() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final CountDownLatch release = new CountDownLatch(1);
        try {
            // One worker + queue capacity 2: occupy the worker, fill the queue, then the next
            // admission must fail fast instead of queueing or running on the caller thread.
            lane.submit("p1", "load", () -> {
                release.await(30, TimeUnit.SECONDS);
                return null;
            });
            lane.submit("p2", "load", () -> null);
            lane.submit("p3", "load", () -> null);
            final PluginLifecycleLane.Invocation<Void> rejected =
                lane.submit("p4", "load", () -> null);
            assertTrue(rejected.rejected);
            assertTrue(rejected.workerDone.isDone());
            final PluginLifecycleLane.AwaitResult<Void> result =
                lane.await(rejected, Duration.ofMillis(50), null);
            assertEquals(PluginLifecycleLane.Outcome.REJECTED, result.outcome);
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }

    @Test
    void stoppedAdmissionRejectsButRetainedDispatchStillRuns() throws Exception {
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        lane.stopAdmission();
        final PluginLifecycleLane.Invocation<Void> rejected =
            lane.submit("p", "load", () -> null);
        assertTrue(rejected.rejected);
        final AtomicBoolean retainedRan = new AtomicBoolean();
        final PluginLifecycleLane.Invocation<Void> retained =
            lane.submitRetained("p", "retained-cleanup", () -> {
                retainedRan.set(true);
                return null;
            });
        assertFalse(retained.rejected);
        retained.workerDone.get(5, TimeUnit.SECONDS);
        assertTrue(retainedRan.get());
        lane.shutdown();
    }

    @Test
    void cancelledPendingTaskNeverRunsPluginWork() throws Exception {
        // workerCount=1 and a blocking first task leaves the second queued; timing out the
        // queued task must settle workerDone without ever entering the plugin body.
        final PluginLifecycleLane lane = new PluginLifecycleLane(POLICY);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean ran = new AtomicBoolean();
        try {
            lane.submit("first", "load", () -> {
                release.await(30, TimeUnit.SECONDS);
                return null;
            });
            final PluginLifecycleLane.Invocation<Void> queued = lane.submit("queued", "load", () -> {
                ran.set(true);
                return null;
            });
            final PluginLifecycleLane.AwaitResult<Void> result =
                lane.await(queued, Duration.ofMillis(60), new PluginLifecycleLease("queued"));
            assertEquals(PluginLifecycleLane.Outcome.TIMED_OUT, result.outcome);
            queued.workerDone.get(5, TimeUnit.SECONDS);
            release.countDown();
            Thread.sleep(150);
            assertFalse(ran.get(), "a PENDING-cancelled task must never enter plugin code");
        } finally {
            release.countDown();
            lane.shutdown();
        }
    }
}
