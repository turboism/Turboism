package dev.turboism.core.runtime.work;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.PluginTask;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PluginWorkExecutorTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.demo";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void taskRunsAsynchronouslyWhenSubmitted() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(PLUGIN_ID, 1, 1, events::add, CLOCK);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        String callerThread = Thread.currentThread().getName();

        // When
        executor.execute(task("action.handle"), () -> {
            workerThread.set(Thread.currentThread().getName());
            completed.countDown();
        });

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNotEquals(callerThread, workerThread.get());
        assertTrue(events.isEmpty());
        executor.shutdown();
    }

    @Test
    void boundedQueueRejectsOverflowAndEmitsDiagnostic() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(PLUGIN_ID, 1, 1, events::add, CLOCK);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        executor.execute(task("event.subscribe"), () -> {
            workerStarted.countDown();
            await(releaseWorker);
        });
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
        executor.execute(task("action.handle"), () -> {});

        // When
        assertDoesNotThrow(() -> executor.execute(task("ui.schedule"), () -> {}));

        // Then
        awaitEvent(events, PluginWorkBudgetEvent.Phase.REJECTED);
        assertTrue(
                events.contains(new PluginWorkBudgetEvent(
                        PLUGIN_ID,
                        "ui.schedule",
                        PluginWorkBudgetEvent.Phase.REJECTED,
                        PluginWorkBudgetEvent.Decision.REJECTED,
                        PluginWorkBudgetEvent.Severity.WARNING)),
                events.toString());
        releaseWorker.countDown();
        executor.shutdown();
    }

    @Test
    void rejectedOverflowNeverRunsOnCallerThread() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(PLUGIN_ID, 1, 1, events::add, CLOCK);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        AtomicReference<String> overflowThread = new AtomicReference<>();
        executor.execute(task("event.subscribe"), () -> {
            workerStarted.countDown();
            await(releaseWorker);
        });
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
        executor.execute(task("action.handle"), () -> {});

        // When
        executor.execute(
                task("ui.schedule"),
                () -> overflowThread.set(Thread.currentThread().getName()));

        // Then
        awaitEvent(events, PluginWorkBudgetEvent.Phase.REJECTED);
        assertEquals(null, overflowThread.get());
        releaseWorker.countDown();
        executor.shutdown();
    }

    @Test
    void shutdownDrainsAndTerminatesPool() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(PLUGIN_ID, 1, 2, events::add, CLOCK);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(3);
        executor.execute(task("event.subscribe"), () -> {
            firstStarted.countDown();
            await(releaseFirst);
            completed.countDown();
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        executor.execute(task("action.handle"), completed::countDown);
        executor.execute(task("ui.schedule"), completed::countDown);

        // When
        releaseFirst.countDown();
        executor.shutdown();

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(executor.isTerminated());
        assertTrue(events.isEmpty());
    }

    @Test
    void slowWorkIsCancelledByTimeLimiterAndEmitsTimedOutDiagnostic() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(
                PLUGIN_ID, PluginWorkExecutorConfiguration.of(50, 1, 1, 50.0f), events::add, CLOCK);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        // When
        executor.execute(task("action.handle"), () -> {
            started.countDown();
            try {
                Thread.sleep(1_000L);
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });

        // Then
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        awaitEvent(events, PluginWorkBudgetEvent.Phase.TIMED_OUT);
        executor.shutdown();
    }

    @Test
    void circuitBreakerOpensAfterFailuresAndFurtherCallsFailFastWithDiagnostic() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(
                PLUGIN_ID, PluginWorkExecutorConfiguration.of(500, 1, 1, 50.0f), events::add, CLOCK);

        // When: each failed call emits only after its completion stage has updated the
        // circuit breaker, so waiting for the diagnostic makes the four-call window deterministic.
        for (int index = 0; index < 4; index++) {
            int failureNumber = index + 1;
            executeAndAwaitFailure(executor, events, index, () -> {
                throw new IllegalStateException("failure " + failureNumber);
            });
        }
        executor.execute(task("ui.schedule"), () -> {
            throw new AssertionError("open circuit must fail fast");
        });

        // Then
        awaitEventCount(events, 5);
        assertEquals(PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN, events.get(4).phase(), events.toString());
        executor.shutdown();
    }

    @Test
    void admittedImmediateFailureRemainsAcceptedAndCompletesFailed() throws Exception {
        final List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        final PluginWorkExecutor executor = new PluginWorkExecutor(
                PLUGIN_ID, PluginWorkExecutorConfiguration.of(500, 1, 1, 50.0f), events::add, CLOCK);

        final PluginWorkSubmission submission = executor.submit(task("action.handle"), () -> {
            throw new IllegalStateException("immediate");
        });

        assertTrue(submission.accepted());
        assertEquals(
                PluginWorkStatus.FAILED,
                submission
                        .completion()
                        .toCompletableFuture()
                        .get(1, TimeUnit.SECONDS)
                        .status());
        executor.shutdown();
    }

    @Test
    void workFailureEmitsFailedDiagnostic() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(
                PLUGIN_ID, PluginWorkExecutorConfiguration.of(500, 1, 1, 50.0f), events::add, CLOCK);

        // When
        executor.execute(task("action.handle"), () -> {
            throw new IllegalStateException("boom");
        });

        // Then
        awaitEvent(events, PluginWorkBudgetEvent.Phase.FAILED);
        assertTrue(
                events.contains(new PluginWorkBudgetEvent(
                        PLUGIN_ID,
                        "action.handle",
                        PluginWorkBudgetEvent.Phase.FAILED,
                        PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
                        PluginWorkBudgetEvent.Severity.ERROR)),
                events.toString());
        executor.shutdown();
    }

    @Test
    void lightweightWorkRunsSuccessfullyWhenExecutorIsHealthy() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(
                PLUGIN_ID, PluginWorkExecutorConfiguration.of(500, 1, 1, 50.0f), events::add, CLOCK);
        CountDownLatch completed = new CountDownLatch(1);

        // When
        executor.execute(task("ui.schedule"), completed::countDown);

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertFalse(events.stream().anyMatch(event -> event.phase() != PluginWorkBudgetEvent.Phase.COMPLETED));
        executor.shutdown();
    }

    @Test
    void workerThreadsAreDaemonAndNamedForPlugin() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(PLUGIN_ID, 1, 1, events::add, CLOCK);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Thread> worker = new AtomicReference<>();

        // When
        executor.execute(task("action.handle"), () -> {
            worker.set(Thread.currentThread());
            completed.countDown();
        });

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(
                worker.get().isDaemon(),
                "plugin work threads must be daemon so a live executor cannot pin the JVM: " + worker.get());
        assertTrue(
                worker.get().getName().contains(PLUGIN_ID),
                "worker thread name must attribute the plugin: " + worker.get().getName());
        executor.shutdown();
    }

    @Test
    void timeoutSchedulerThreadsAreDaemonAndNamedForPlugin() throws InterruptedException {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutor executor = new PluginWorkExecutor(
                PLUGIN_ID, PluginWorkExecutorConfiguration.of(50, 1, 1, 50.0f), events::add, CLOCK);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> timeoutThread = new AtomicReference<>();

        // When: the timeout action runs on the timeout scheduler thread.
        executor.submit(
                task("action.handle"),
                () -> {
                    started.countDown();
                    try {
                        Thread.sleep(1_000L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                },
                () -> timeoutThread.set(Thread.currentThread()));

        // Then
        assertTrue(started.await(1, TimeUnit.SECONDS));
        awaitEvent(events, PluginWorkBudgetEvent.Phase.TIMED_OUT);
        assertTrue(timeoutThread.get().isDaemon(), "timeout scheduler threads must be daemon: " + timeoutThread.get());
        assertTrue(
                timeoutThread.get().getName().contains(PLUGIN_ID),
                "timeout thread name must attribute the plugin: "
                        + timeoutThread.get().getName());
        executor.shutdown();
    }

    @Test
    void releaseShutsClaimedSetWithoutTouchingItsReplacement() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutorRegistry registry = new PluginWorkExecutorRegistry(1, 1, events::add, CLOCK);
        PluginExecutorSet first = registry.claim(PLUGIN_ID);

        // When the captured generation is released
        registry.release(PLUGIN_ID, first);

        // Then the claimed set is gone and a later claim sees a fresh set
        assertTrue(first.isTerminated());
        PluginExecutorSet second = registry.claim(PLUGIN_ID);
        assertNotSame(first, second, "a later claim must produce a fresh executor set");
        assertTrue(second.tasks().submit(task("action.handle"), () -> {}).accepted());

        // And a stale release of the old generation cannot close the replacement
        registry.release(PLUGIN_ID, first);
        assertFalse(second.isTerminated());
        assertTrue(second.tasks().submit(task("action.handle"), () -> {}).accepted());

        registry.shutdownAll();
        assertTrue(second.isTerminated());
    }

    @Test
    void registryCreatesOneExecutorPerPluginAndShutdownIsIdempotent() {
        // Given
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        PluginWorkExecutorRegistry registry = new PluginWorkExecutorRegistry(1, 1, events::add, CLOCK);

        // When
        PluginWorkExecutor first = registry.get(PLUGIN_ID);
        PluginWorkExecutor second = registry.get(PLUGIN_ID);
        PluginWorkExecutor other = registry.get("dev.turboism.plugin.other");
        registry.shutdown(PLUGIN_ID);
        registry.shutdown(PLUGIN_ID);

        // Then
        assertSame(first, second);
        assertNotSame(first, other);
        assertTrue(first.isTerminated());
        other.shutdown();
    }

    private static PluginTask task(String type) {
        return new PluginTask(type, PLUGIN_ID, "payload for " + type, "none");
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void executeAndAwaitFailure(
            PluginWorkExecutor executor, List<PluginWorkBudgetEvent> events, int eventIndex, Runnable work) {
        CountDownLatch workCompleted = new CountDownLatch(1);
        executor.execute(task("action.handle"), () -> {
            try {
                work.run();
            } finally {
                workCompleted.countDown();
            }
        });
        await(workCompleted);
        awaitEventCount(events, eventIndex + 1);
        assertEquals(PluginWorkBudgetEvent.Phase.FAILED, events.get(eventIndex).phase(), events.toString());
    }

    private static void awaitEventCount(List<PluginWorkBudgetEvent> events, int expectedCount) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (events.size() >= expectedCount) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Expected at least " + expectedCount + " plugin work budget events in " + events);
    }

    private static void awaitEvent(List<PluginWorkBudgetEvent> events, PluginWorkBudgetEvent.Phase phase) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (events.stream().anyMatch(event -> event.phase() == phase)) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("Missing plugin work budget event phase " + phase + " in " + events);
    }
}
