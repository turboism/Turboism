package dev.turboism.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.CancellationContext;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.contract.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class RuntimeUiSchedulerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);
    private static final Duration SHORT_DELAY = Duration.ofMillis(150);

    @Test
    void runOnUiThreadReturnsImmediatelyAndSchedulesAsynchronously() throws InterruptedException {
        // Given
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch releaseWork = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);

        // When
        Registration registration = uiScheduler.runOnUiThread(() -> {
            await(releaseWork);
            completed.countDown();
        });

        // Then
        assertEquals(1, completed.getCount());
        releaseWork.countDown();
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        registration.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void runtimeWorkerDoesNotCompleteBeforeBlockingEdtWorkFinishes() throws InterruptedException {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch enteredEdt = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        CountDownLatch completedEdt = new CountDownLatch(1);
        CountDownLatch nextWorkStarted = new CountDownLatch(1);

        try {
            uiScheduler.runOnUiThread(() -> {
                enteredEdt.countDown();
                await(releaseEdt);
                completedEdt.countDown();
            });
            assertTrue(enteredEdt.await(1, TimeUnit.SECONDS));

            assertTrue(runtimeScheduler.dispatch(
                    new PluginTask("action.handle", "dev.turboism.plugin.demo", "work after UI callback", "none"),
                    nextWorkStarted::countDown));

            assertFalse(nextWorkStarted.await(200, TimeUnit.MILLISECONDS));
            releaseEdt.countDown();
            assertTrue(completedEdt.await(1, TimeUnit.SECONDS));
            assertTrue(nextWorkStarted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseEdt.countDown();
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void exceptionsFromEdtWorkFeedRuntimeFailureDiagnostics() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler runtimeScheduler = runtimeScheduler(events, 500L);
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");

        try {
            uiScheduler.runOnUiThread(() -> {
                throw new IllegalStateException("boom");
            });

            awaitEvent(events, PluginWorkBudgetEvent.Phase.FAILED);
            assertTrue(
                    events.contains(new PluginWorkBudgetEvent(
                            "dev.turboism.plugin.demo",
                            "ui.schedule",
                            PluginWorkBudgetEvent.Phase.FAILED,
                            PluginWorkBudgetEvent.Decision.LIGHTWEIGHT,
                            PluginWorkBudgetEvent.Severity.ERROR)),
                    events.toString());
        } finally {
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void throwingEdtCallbacksEmitFailuresWithoutOpeningTheRuntimeCircuit() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler runtimeScheduler = runtimeScheduler(events, 500L);
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");

        try {
            for (int index = 0; index < 4; index++) {
                uiScheduler.runOnUiThread(() -> {
                    throw new IllegalStateException("boom");
                });
                awaitEventCount(events, index + 1);
                assertEquals(
                        PluginWorkBudgetEvent.Phase.FAILED, events.get(index).phase(), events.toString());
            }

            // The breaker must stay closed: a failing callback is the plugin's bug, not a reason to
            // disable the plugin's UI, and EDT latency must never be charged here at all.
            AtomicInteger ran = new AtomicInteger();
            assertDoesNotThrow(() -> uiScheduler.runOnUiThread(ran::incrementAndGet));
            awaitCondition(() -> ran.get() == 1);
            assertTrue(
                    events.stream().noneMatch(event -> event.phase() == PluginWorkBudgetEvent.Phase.CIRCUIT_OPEN),
                    events.toString());
        } finally {
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void edtCallbackReceivesAndClearsRuntimeCancellationContext() throws InterruptedException {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean tokenBound = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            uiScheduler.runOnUiThread(() -> {
                try {
                    tokenBound.set(CancellationContext.get() != null);
                    assertTrue(SwingUtilities.isEventDispatchThread());
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    completed.countDown();
                }
            });

            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(null, failure.get());
            assertTrue(tokenBound.get());
            AtomicBoolean contextCleared = new AtomicBoolean();
            SwingUtilities.invokeLater(() -> contextCleared.set(CancellationContext.get() == null));
            flushEdt();
            assertTrue(contextCleared.get());
        } finally {
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void uiWorkQueuedDuringAnEdtStallStillRunsWhenTheEdtRecovers() throws InterruptedException {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler runtimeScheduler = runtimeScheduler(events, 50L);
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            await(releaseEdt);
        });
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS));

        try {
            uiScheduler.runOnUiThread(executions::incrementAndGet);

            Thread.sleep(400L);
            releaseEdt.countDown();
            awaitCondition(() -> executions.get() == 1);
        } finally {
            releaseEdt.countDown();
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void blockedEdtDoesNotChargeUiWorkToTheWorkBudget() throws InterruptedException {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        RuntimeScheduler runtimeScheduler = runtimeScheduler(events, 50L);
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch enteredEdt = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        CountDownLatch completedEdt = new CountDownLatch(1);

        try {
            uiScheduler.runOnUiThread(() -> {
                enteredEdt.countDown();
                await(releaseEdt);
                completedEdt.countDown();
            });

            assertTrue(enteredEdt.await(1, TimeUnit.SECONDS));
            // Hold the EDT far past the work budget. A large model load looks exactly like this.
            Thread.sleep(400L);
            assertTrue(
                    events.stream().noneMatch(event -> event.phase() == PluginWorkBudgetEvent.Phase.TIMED_OUT),
                    "EDT queue latency is host-owned and must not be charged to the plugin: " + events);
            assertEquals(1, completedEdt.getCount(), "the budget must never preempt running EDT work");

            releaseEdt.countDown();
            assertTrue(completedEdt.await(1, TimeUnit.SECONDS));
        } finally {
            releaseEdt.countDown();
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void acceptedUiWorkRunsOnTheSwingEventDispatchThread() throws InterruptedException {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean onEdt = new AtomicBoolean();

        uiScheduler.runOnUiThread(() -> {
            onEdt.set(SwingUtilities.isEventDispatchThread());
            completed.countDown();
        });

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(onEdt.get());
        uiScheduler.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void runOnUiThreadCalledFromEdtReturnsWithoutDeadlocking() throws InterruptedException {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch returned = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try {
            SwingUtilities.invokeLater(() -> {
                try {
                    uiScheduler.runOnUiThread(completed::countDown);
                } catch (Throwable exception) {
                    failure.set(exception);
                } finally {
                    returned.countDown();
                }
            });

            assertTrue(returned.await(1, TimeUnit.SECONDS));
            assertEquals(null, failure.get());
            assertTrue(completed.await(1, TimeUnit.SECONDS));
        } finally {
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void rejectedUiWorkFailsImmediately() {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        runtimeScheduler.shutdown();

        IllegalStateException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, () -> uiScheduler.runOnUiThread(() -> {}));

        assertEquals("UI_SCHEDULER_REJECTED", failure.getMessage());
        uiScheduler.close();
    }

    @Test
    void delayedUiWorkAlsoRunsOnTheSwingEventDispatchThread() throws InterruptedException {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean onEdt = new AtomicBoolean();

        uiScheduler.runOnUiThreadLater(
                () -> {
                    onEdt.set(SwingUtilities.isEventDispatchThread());
                    completed.countDown();
                },
                Duration.ZERO);

        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(onEdt.get());
        uiScheduler.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void registrationCancelsUiWorkQueuedBehindAnotherEdtCallback() throws InterruptedException {
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        CountDownLatch edtBlocked = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            await(releaseEdt);
        });
        assertTrue(edtBlocked.await(1, TimeUnit.SECONDS));

        try {
            Registration registration = uiScheduler.runOnUiThread(executions::incrementAndGet);
            registration.close();
            releaseEdt.countDown();
            flushEdt();

            assertEquals(0, executions.get());
        } finally {
            releaseEdt.countDown();
            uiScheduler.close();
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void registrationCanCancelPendingUiWork() throws InterruptedException {
        // Given
        RuntimeScheduler runtimeScheduler = runtimeScheduler();
        RuntimeUiScheduler uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
        AtomicInteger executions = new AtomicInteger();

        // When
        Registration registration = uiScheduler.runOnUiThreadLater(executions::incrementAndGet, SHORT_DELAY);
        registration.close();

        // Then
        TimeUnit.MILLISECONDS.sleep(SHORT_DELAY.toMillis() * 2);
        assertEquals(0, executions.get());
        runtimeScheduler.shutdown();
    }

    private static RuntimeScheduler runtimeScheduler() {
        return runtimeScheduler(new CopyOnWriteArrayList<>(), 500L);
    }

    private static RuntimeScheduler runtimeScheduler(List<PluginWorkBudgetEvent> events, long timeoutMillis) {
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(timeoutMillis, 1, 4, events::add, CLOCK),
                events::add);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void flushEdt() throws InterruptedException {
        CountDownLatch flushed = new CountDownLatch(1);
        SwingUtilities.invokeLater(flushed::countDown);
        assertTrue(flushed.await(1, TimeUnit.SECONDS));
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("condition was not met within 5 seconds");
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
