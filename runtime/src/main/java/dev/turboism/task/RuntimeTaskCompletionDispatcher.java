package dev.turboism.task;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.runtime.work.PluginWorkStatus;
import dev.turboism.core.runtime.work.PluginWorkSubmission;
import dev.turboism.core.runtime.RuntimeScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class RuntimeTaskCompletionDispatcher {

    enum DispatchKind {
        TASK_HANDLE_SETTLEMENT,
        PLUGIN_CONTINUATION
    }

    private static final Duration RETRY_DELAY = Duration.ofMillis(5);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String pluginId;
    private final RuntimeScheduler runtimeScheduler;
    private final CleanupEvidenceCollector cleanupEvidence;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<IllegalStateException> dispatchFailure = new AtomicReference<>();
    private final Object quiescenceMonitor = new Object();
    private volatile boolean cleanup;
    private boolean closed;

    RuntimeTaskCompletionDispatcher(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
        this.cleanupEvidence = Objects.requireNonNull(cleanupEvidence, "cleanupEvidence");
    }

    void dispatchTaskHandleSettlement(final Runnable action) {
        dispatch(DispatchKind.TASK_HANDLE_SETTLEMENT, action);
    }

    void dispatchPluginContinuation(final Runnable action) {
        dispatch(DispatchKind.PLUGIN_CONTINUATION, action);
    }

    void beginCleanup() {
        synchronized (quiescenceMonitor) {
            if (!closed) {
                cleanup = true;
            }
        }
    }

    void awaitQuiescence() {
        awaitQuiescence(CLOSE_TIMEOUT, false);
    }

    void awaitQuiescence(final Duration timeout) {
        awaitQuiescence(timeout, false);
    }

    void closeAndAwaitQuiescence() {
        awaitQuiescence(CLOSE_TIMEOUT, true);
    }

    int pendingCount() {
        return pending.get();
    }

    private void dispatch(final DispatchKind kind, final Runnable action) {
        synchronized (quiescenceMonitor) {
            if (closed) {
                throw new IllegalStateException(
                    "Plugin task completion dispatcher is already closed."
                );
            }
            pending.incrementAndGet();
        }
        attempt(new DispatchedAction(kind, action));
    }

    private void awaitQuiescence(final Duration timeout, final boolean closeAfterWait) {
        Objects.requireNonNull(timeout, "timeout");
        final long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (quiescenceMonitor) {
            while (pending.get() > 0) {
                final IllegalStateException failure = dispatchFailure.get();
                if (failure != null) {
                    if (closeAfterWait) {
                        closed = true;
                    }
                    throw failure;
                }
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    if (closeAfterWait) {
                        closed = true;
                    }
                    throw new IllegalStateException(
                        "Plugin task completions did not quiesce before scope close."
                    );
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(quiescenceMonitor, remaining);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    if (closeAfterWait) {
                        closed = true;
                    }
                    throw new IllegalStateException(
                        "Interrupted while waiting for plugin task completion quiescence.",
                        exception
                    );
                }
            }
            if (closeAfterWait) {
                closed = true;
            }
        }
    }

    private void attempt(final DispatchedAction dispatched) {
        if (dispatched.started.get()) {
            return;
        }
        final PluginWorkSubmission submission = runtimeScheduler.submitCompletion(
            pluginId,
            dispatched::run
        );
        if (!submission.accepted()) {
            retry(dispatched);
            return;
        }
        submission.completion().whenComplete((result, failure) -> {
            if (dispatched.started.get()) {
                return;
            }
            if (failure != null
                || result == null
                || result.status() != PluginWorkStatus.SUCCEEDED) {
                retry(dispatched);
            }
        });
    }

    private void retry(final DispatchedAction dispatched) {
        if (dispatched.started.get()) {
            return;
        }
        final var timer = runtimeScheduler.schedule(
            RETRY_DELAY,
            () -> attempt(dispatched)
        );
        if (!timer.accepted()) {
            failDispatch();
        }
    }

    private void failDispatch() {
        dispatchFailure.compareAndSet(
            null,
            new IllegalStateException(
                "Plugin task completion could not be dispatched through the plugin executor."
            )
        );
        synchronized (quiescenceMonitor) {
            quiescenceMonitor.notifyAll();
        }
    }

    private void finished(final DispatchKind kind) {
        if (cleanup) {
            if (kind == DispatchKind.TASK_HANDLE_SETTLEMENT) {
                cleanupEvidence.taskCompletionSettled();
            } else {
                cleanupEvidence.pluginContinuationDrained();
            }
        }
        if (pending.decrementAndGet() == 0) {
            synchronized (quiescenceMonitor) {
                quiescenceMonitor.notifyAll();
            }
        }
    }

    private final class DispatchedAction {
        private final DispatchKind kind;
        private final Runnable action;
        private final AtomicBoolean started = new AtomicBoolean(false);

        private DispatchedAction(final DispatchKind kind, final Runnable action) {
            this.kind = Objects.requireNonNull(kind, "kind");
            this.action = Objects.requireNonNull(action, "action");
        }

        private void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                action.run();
            } finally {
                finished(kind);
            }
        }
    }
}
