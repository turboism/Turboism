package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpExecutionBridgeTest {

    @Test
    void queuedUiTimeoutPreventsLateExecutionEvenWhenRegistrationCannotCancel() {
        final QueuedUi scheduler = new QueuedUi();
        final McpExecutionBridge bridge = new McpExecutionBridge(scheduler, Duration.ofMillis(30));
        final AtomicBoolean written = new AtomicBoolean();

        assertThrows(McpExecutionBridge.ExecutionFailure.class, () -> bridge.ui(() -> {
            written.set(true);
            return "committed";
        }));

        scheduler.queued.get().run();
        assertFalse(written.get());
    }

    @Test
    void queuedUiInterruptionPreventsLateExecution() throws Exception {
        final QueuedUi scheduler = new QueuedUi();
        final McpExecutionBridge bridge = new McpExecutionBridge(scheduler);
        final AtomicBoolean written = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread request = new Thread(() -> {
            try {
                bridge.ui(() -> {
                    written.set(true);
                    return "committed";
                });
            } catch (Throwable caught) {
                failure.set(caught);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "queued-mcp-request");
        request.start();
        try {
            assertTrue(scheduler.scheduled.await(5, TimeUnit.SECONDS));
        } finally {
            request.interrupt();
            request.join(5_000);
        }
        assertFalse(request.isAlive());
        assertInstanceOf(McpExecutionBridge.ExecutionFailure.class, failure.get());
        assertTrue(interrupted.get());
        scheduler.queued.get().run();
        assertFalse(written.get());
    }

    @Test
    void startedUiWorkWaitsPastDeadlineForItsDefinitiveResult() throws Exception {
        assertStartedWorkCompletes(false, null);
    }

    @Test
    void interruptedWaiterStillReceivesStartedUiResultAndRestoresInterrupt() throws Exception {
        assertStartedWorkCompletes(true, null);
    }

    @Test
    void startedUiFailureIsNotReplacedByQueueTimeout() throws Exception {
        assertStartedWorkCompletes(false, new AssertionError("host failure"));
    }

    @Test
    void completionStageStillTimesOutAndCancelsThePendingStage() {
        final McpExecutionBridge bridge = new McpExecutionBridge(new QueuedUi(), Duration.ofMillis(30));
        final CompletableFuture<String> pending = new CompletableFuture<>();
        assertThrows(McpExecutionBridge.ExecutionFailure.class, () -> bridge.stage(() -> pending));
        assertTrue(pending.isCancelled());
    }

    private static void assertStartedWorkCompletes(
        final boolean interruptWaiter,
        final AssertionError hostFailure
    ) throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean written = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicReference<String> value = new AtomicReference<>();
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        final AsyncUi scheduler = new AsyncUi();
        final McpExecutionBridge bridge = new McpExecutionBridge(scheduler, Duration.ofMillis(30));
        final Thread request = new Thread(() -> {
            try {
                value.set(bridge.ui(() -> {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("test did not release the host");
                        }
                    } catch (InterruptedException caught) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(caught);
                    }
                    if (hostFailure != null) throw hostFailure;
                    written.set(true);
                    return "committed";
                }));
            } catch (Throwable caught) {
                failure.set(caught);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
                finished.complete(null);
            }
        }, "started-mcp-request");
        request.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            if (interruptWaiter) request.interrupt();
            assertThrows(TimeoutException.class, () -> finished.get(150, TimeUnit.MILLISECONDS));
            assertFalse(written.get());
        } finally {
            release.countDown();
            request.join(5_000);
            if (scheduler.thread.get() != null) scheduler.thread.get().join(5_000);
        }
        assertFalse(request.isAlive());
        assertEquals(interruptWaiter, interrupted.get());
        if (hostFailure == null) {
            assertNull(failure.get());
            assertTrue(written.get());
            assertEquals("committed", value.get());
        } else {
            assertSame(hostFailure, failure.get());
            assertFalse(written.get());
            assertNull(value.get());
        }
    }

    private static final class QueuedUi implements UiScheduler {
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final CountDownLatch scheduled = new CountDownLatch(1);

        @Override public Registration runOnUiThread(final Runnable work) {
            queued.set(work);
            scheduled.countDown();
            return () -> { };
        }

        @Override public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class AsyncUi implements UiScheduler {
        final AtomicReference<Thread> thread = new AtomicReference<>();

        @Override public Registration runOnUiThread(final Runnable work) {
            final Thread ui = new Thread(work, "execution-bridge-test-ui");
            thread.set(ui);
            ui.start();
            return () -> { };
        }

        @Override public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
            throw new UnsupportedOperationException();
        }
    }
}
