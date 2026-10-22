package dev.turboism.core.runtime.work;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeCancellationToken;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.test.concurrent.Await;
import dev.turboism.test.concurrent.ManualScheduledExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PluginLongLaneTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration DEFAULT_THRESHOLD = Duration.ofSeconds(30);
    private static final Duration DEFAULT_INTERVAL = Duration.ofMinutes(5);

    private final ManualScheduledExecutor monitor = new ManualScheduledExecutor();
    private final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();

    @AfterEach
    void resetDiagnostics() {
        RuntimeDiagnostics.clear();
    }

    @Test
    void longTaskRunsPastTaskExecutorBudgetWithoutInterrupt() throws Exception {
        final PluginLongLane lane = lane(2, 8);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        final PluginWorkSubmission submission =
                lane.submit(task("plugin.long.normal"), new RuntimeCancellationToken(), () -> {
                    started.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException exception) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                });
        try {
            assertTrue(submission.accepted());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            monitor.advanceBy(Duration.ofMillis(700));
            assertFalse(submission.completion().toCompletableFuture().isDone());
            assertFalse(interrupted.get(), "long lane work must never be interrupted");
        } finally {
            release.countDown();
        }
        assertEquals(
                PluginWorkStatus.SUCCEEDED,
                submission
                        .completion()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS)
                        .status());
        assertFalse(interrupted.get(), "long lane work must never be interrupted");
        lane.shutdown();
    }

    @Test
    void queuedTaskIsCancelledWithoutRunning() throws Exception {
        final PluginLongLane lane = lane(1, 8);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        lane.submit(task("plugin.long.normal"), new RuntimeCancellationToken(), () -> {
            blockerStarted.countDown();
            awaitQuietly(releaseBlocker);
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        final AtomicBoolean ran = new AtomicBoolean();
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        final PluginWorkSubmission queued = lane.submit(task("plugin.long.normal"), token, () -> ran.set(true));
        assertTrue(queued.accepted());

        token.cancel();

        final PluginWorkResult result =
                queued.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(PluginWorkStatus.CANCELED, result.status());
        assertFalse(ran.get(), "cancelled queued work must never run");

        releaseBlocker.countDown();
        lane.shutdown();
    }

    @Test
    void runningTaskIsOnlyFlaggedOnCancel() throws Exception {
        final PluginLongLane lane = lane(1, 8);
        final RuntimeCancellationToken token = new RuntimeCancellationToken();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean sawCancelFlag = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();

        lane.submit(task("plugin.long.normal"), token, () -> {
            started.countDown();
            while (!token.isCancellationRequested() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                }
            }
            sawCancelFlag.set(token.isCancellationRequested());
            completed.countDown();
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        token.cancel();

        assertTrue(completed.await(2, TimeUnit.SECONDS));
        assertTrue(sawCancelFlag.get(), "running work observes the cooperative cancel flag");
        assertFalse(interrupted.get(), "cancel must not interrupt running long work");
        lane.shutdown();
    }

    @Test
    void shutdownCancelsQueuedAndWaitsForRunningWithoutInterrupt() throws Exception {
        final PluginLongLane lane = lane(1, 8);
        final RuntimeCancellationToken runningToken = new RuntimeCancellationToken();
        final CountDownLatch runningStarted = new CountDownLatch(1);
        final CountDownLatch runningFinished = new CountDownLatch(1);
        final CountDownLatch releaseRunning = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        lane.submit(task("plugin.long.normal"), runningToken, () -> {
            runningStarted.countDown();
            while (!runningToken.isCancellationRequested()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException exception) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            awaitQuietly(releaseRunning);
            runningFinished.countDown();
        });
        assertTrue(runningStarted.await(1, TimeUnit.SECONDS));

        final AtomicBoolean queuedRan = new AtomicBoolean();
        final PluginWorkSubmission queued =
                lane.submit(task("plugin.long.normal"), new RuntimeCancellationToken(), () -> queuedRan.set(true));
        assertTrue(queued.accepted());
        queued.completion().whenComplete((result, failure) -> releaseRunning.countDown());

        lane.shutdown();

        assertEquals(
                PluginWorkStatus.CANCELED,
                queued.completion()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS)
                        .status(),
                "queued work settles CANCELED at shutdown");
        assertFalse(queuedRan.get());
        assertTrue(
                runningFinished.await(2, TimeUnit.SECONDS),
                "shutdown sets the running task's cancellation token so cooperative work exits");
        assertFalse(interrupted.get(), "shutdown must never interrupt plugin code");
        assertTrue(lane.isTerminated());
    }

    @Test
    void shutdownDoesNotInterruptAStubbornTask() throws Exception {
        final PluginLongLane lane = new PluginLongLane(
                PLUGIN_ID, 1, 8, DEFAULT_THRESHOLD, DEFAULT_INTERVAL, events::add, Duration.ofMillis(50));
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicBoolean interrupted = new AtomicBoolean();
        lane.submit(task("plugin.long.normal"), new RuntimeCancellationToken(), () -> {
            started.countDown();
            try {
                // Ignores cancellation entirely: only the test latch ends it.
                awaitQuietly(release);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
                finished.countDown();
            }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));

        final Thread shutdownThread = new Thread(lane::shutdown, "lane-shutdown");
        shutdownThread.start();
        shutdownThread.join(TimeUnit.SECONDS.toMillis(8));
        assertFalse(shutdownThread.isAlive(), "shutdown returns after its bounded wait");

        release.countDown();
        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertFalse(interrupted.get(), "a stubborn task is left running, never interrupted");
    }

    @Test
    void longRunningDiagnosticIsEmittedOncePerTask() throws Exception {
        final List<String> messages = new CopyOnWriteArrayList<>();
        RuntimeDiagnostics.install((level, component, message, failure) -> messages.add(message));
        try {
            final PluginLongLane lane = lane(2, 8, Duration.ofMillis(50), Duration.ofHours(1));
            final CountDownLatch release = new CountDownLatch(1);
            final var alpha =
                    lane.submit(task("plugin.long.alpha"), new RuntimeCancellationToken(), () -> awaitQuietly(release));
            final var beta =
                    lane.submit(task("plugin.long.beta"), new RuntimeCancellationToken(), () -> awaitQuietly(release));
            try {
                Await.condition(() -> monitor.pendingTaskCount() == 2);
                monitor.advanceBy(Duration.ofMillis(49));
                assertTrue(messages.isEmpty(), "no report before the threshold");
                monitor.advanceBy(Duration.ofMillis(1));
                assertEquals(2, messages.size());
                monitor.advanceBy(Duration.ofHours(1).minusMillis(1));
                assertEquals(2, messages.size(), "no repeated report before the interval");
            } finally {
                release.countDown();
            }
            alpha.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            beta.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
            lane.shutdown();
            monitor.advanceBy(Duration.ofDays(1));

            final List<String> reports = messages.stream()
                    .filter(message -> message.contains("LONG_TASK_RUNNING"))
                    .toList();
            assertEquals(2, reports.size(), "one diagnostic per long-running task: " + reports);
            assertTrue(reports.stream().anyMatch(message -> message.contains("plugin.long.alpha")));
            assertTrue(reports.stream().anyMatch(message -> message.contains("plugin.long.beta")));
            assertTrue(reports.stream().allMatch(message -> message.contains(PLUGIN_ID)));
        } finally {
            RuntimeDiagnostics.clear();
        }
    }

    @Test
    void submitAfterShutdownIsRejected() {
        final PluginLongLane lane = lane(1, 8);
        lane.shutdown();

        final PluginWorkSubmission submission =
                lane.submit(task("plugin.long.normal"), new RuntimeCancellationToken(), () -> {});

        assertFalse(submission.accepted());
        assertEquals(PluginWorkStatus.RUNTIME_UNAVAILABLE, submission.rejectionStatus());
    }

    @Test
    void releasedExecutorSetShutsLongLaneAndNewClaimIsFresh() throws Exception {
        final PluginWorkExecutorRegistry registry = new PluginWorkExecutorRegistry(1, 2, events::add, CLOCK);
        final PluginExecutorSet first = registry.claim(PLUGIN_ID);
        final CountDownLatch ran = new CountDownLatch(1);
        assertTrue(first.longLane()
                .submit(task("plugin.long.normal"), new RuntimeCancellationToken(), ran::countDown)
                .accepted());
        assertTrue(ran.await(1, TimeUnit.SECONDS));

        registry.release(PLUGIN_ID, first);

        assertFalse(first.longLane()
                .submit(task("plugin.long.normal"), new RuntimeCancellationToken(), () -> {})
                .accepted());
        assertTrue(first.isTerminated());

        final PluginExecutorSet second = registry.claim(PLUGIN_ID);
        assertNotSame(first, second);
        final CountDownLatch secondRan = new CountDownLatch(1);
        assertTrue(second.longLane()
                .submit(task("plugin.long.normal"), new RuntimeCancellationToken(), secondRan::countDown)
                .accepted());
        assertTrue(secondRan.await(1, TimeUnit.SECONDS));

        registry.shutdownAll();
        assertTrue(second.isTerminated());
    }

    private PluginLongLane lane(final int concurrency, final int queueCapacity) {
        return lane(concurrency, queueCapacity, DEFAULT_THRESHOLD, DEFAULT_INTERVAL);
    }

    private PluginLongLane lane(
            final int concurrency, final int queueCapacity, final Duration threshold, final Duration interval) {
        return new PluginLongLane(
                PLUGIN_ID,
                concurrency,
                queueCapacity,
                threshold,
                interval,
                events::add,
                Duration.ofSeconds(5),
                monitor);
    }

    private static PluginTask task(final String type) {
        return new PluginTask(type, PLUGIN_ID, "payload for " + type, "none");
    }

    private static void awaitQuietly(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
