package dev.turboism.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bounded synchronous UI teardown ({@code runUiTeardown}): inline on the EDT, queued from other
 * threads, bounded return while the EDT is wedged, interrupt preservation, failure propagation
 * and rejection after the scheduler closed.
 */
class RuntimeUiSchedulerTeardownTest {

    private static final Duration SHORT_BOUND = Duration.ofMillis(150);
    private static final Duration GENEROUS_BOUND = Duration.ofSeconds(10);

    private RuntimeScheduler runtimeScheduler;
    private RuntimeUiScheduler uiScheduler;
    private final CountDownLatch releaseEdt = new CountDownLatch(1);

    @BeforeEach
    void setUp() {
        runtimeScheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(
                        500L, 1, 4, event -> {}, Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC)),
                new NoopSidecarDispatcher(),
                (PluginWorkBudgetEvent event) -> {});
        uiScheduler = new RuntimeUiScheduler(runtimeScheduler, "dev.turboism.plugin.demo");
    }

    @AfterEach
    void tearDown() throws Exception {
        releaseEdt.countDown();
        drainEdt();
        Thread.interrupted();
        uiScheduler.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void runsInlineAndCompletesWhenCalledOnTheEdt() throws Exception {
        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        final AtomicReference<Boolean> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            result.set(uiScheduler.runUiTeardown(() -> ranOn.set(Thread.currentThread()), SHORT_BOUND));
            assertSame(Thread.currentThread(), ranOn.get(), "must run inline instead of queueing and self-deadlocking");
        });

        assertTrue(result.get());
    }

    @Test
    void runsOnTheEdtAndWaitsForCompletionWhenCalledOffTheEdt() {
        final AtomicBoolean onEdt = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();

        final boolean completed = uiScheduler.runUiTeardown(
                () -> {
                    onEdt.set(SwingUtilities.isEventDispatchThread());
                    finished.set(true);
                },
                GENEROUS_BOUND);

        assertTrue(completed);
        assertTrue(onEdt.get());
        assertTrue(finished.get(), "caller must not return before the cleanup finished");
    }

    @Test
    void returnsWithinTheBoundWhileTheEdtIsHungAndStillRunsTheCleanupOnceLater() throws Exception {
        wedgeEdt();
        final AtomicInteger executions = new AtomicInteger();

        final long start = System.nanoTime();
        final boolean completed = uiScheduler.runUiTeardown(executions::incrementAndGet, SHORT_BOUND);
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(completed, "a deferred cleanup must be reported as not completed");
        assertTrue(elapsedMillis < 5_000, "caller must be released by the accept bound, took " + elapsedMillis);
        assertEquals(0, executions.get(), "the wedged EDT cannot have run the cleanup yet");

        releaseEdt.countDown();
        drainEdt();
        assertEquals(1, executions.get(), "deferred cleanup must still run exactly once");
    }

    @Test
    void interruptWhileQueuedPreservesTheInterruptAndDefersTheCleanup() throws Exception {
        wedgeEdt();
        final AtomicInteger executions = new AtomicInteger();
        final AtomicReference<Boolean> completed = new AtomicReference<>();
        final AtomicBoolean interruptRestored = new AtomicBoolean();
        final CountDownLatch callerDone = new CountDownLatch(1);
        final Thread caller = new Thread(
                () -> {
                    completed.set(uiScheduler.runUiTeardown(executions::incrementAndGet, GENEROUS_BOUND));
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                    callerDone.countDown();
                },
                "ui-teardown-interrupt-test");
        caller.setDaemon(true);
        caller.start();
        // Let the caller reach the accept wait, then interrupt it.
        TimeUnit.MILLISECONDS.sleep(100);
        caller.interrupt();

        assertTrue(callerDone.await(5, TimeUnit.SECONDS), "interrupt must release the caller");
        assertFalse(completed.get());
        assertTrue(interruptRestored.get(), "interrupt status must be preserved for the caller");

        releaseEdt.countDown();
        drainEdt();
        assertEquals(1, executions.get(), "interrupt defers the cleanup; it must not drop it");
    }

    @Test
    void failureWhileTheCallerWaitsPropagatesUnchanged() {
        final IllegalStateException failure = new IllegalStateException("window dispose broke");

        final IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> uiScheduler.runUiTeardown(
                        () -> {
                            throw failure;
                        },
                        GENEROUS_BOUND));

        assertSame(failure, thrown);
    }

    @Test
    void rejectsNullsAndNonPositiveBounds() {
        assertThrows(NullPointerException.class, () -> uiScheduler.runUiTeardown(null, SHORT_BOUND));
        assertThrows(NullPointerException.class, () -> uiScheduler.runUiTeardown(() -> {}, null));
        assertThrows(IllegalArgumentException.class, () -> uiScheduler.runUiTeardown(() -> {}, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> uiScheduler.runUiTeardown(() -> {}, Duration.ofMillis(-1)));
    }

    @Test
    void callAfterTheSchedulerClosedIsRejectedWithoutRunningTheCleanup() {
        final AtomicInteger executions = new AtomicInteger();
        uiScheduler.close();

        final IllegalStateException failure = assertThrows(
                IllegalStateException.class, () -> uiScheduler.runUiTeardown(executions::incrementAndGet, SHORT_BOUND));

        assertEquals("UI_SCHEDULER_CLOSED", failure.getMessage());
        assertEquals(0, executions.get());
    }

    private void wedgeEdt() throws InterruptedException {
        final CountDownLatch wedged = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            wedged.countDown();
            try {
                releaseEdt.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(wedged.await(5, TimeUnit.SECONDS), "EDT must pick up the wedge task");
    }

    private static void drainEdt() throws Exception {
        final CountDownLatch drained = new CountDownLatch(1);
        SwingUtilities.invokeLater(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS), "EDT must drain");
    }

    private static final class NoopSidecarDispatcher implements SidecarDispatcher {
        @Override
        public CompletionStage<SidecarResult> dispatch(final PluginTask task, final Runnable callback) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
