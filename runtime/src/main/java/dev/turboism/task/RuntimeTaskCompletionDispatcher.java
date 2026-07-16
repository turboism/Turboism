package dev.turboism.task;

import dev.turboism.core.runtime.CallbackExecutionStatus;
import dev.turboism.core.runtime.CallbackSubmission;
import dev.turboism.core.runtime.RuntimeScheduler;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class RuntimeTaskCompletionDispatcher {

    private static final Duration RETRY_DELAY = Duration.ofMillis(5);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final String pluginId;
    private final RuntimeScheduler runtimeScheduler;
    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicReference<IllegalStateException> dispatchFailure = new AtomicReference<>();
    private final Object quiescenceMonitor = new Object();
    private boolean closed;

    RuntimeTaskCompletionDispatcher(
        final String pluginId,
        final RuntimeScheduler runtimeScheduler
    ) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.runtimeScheduler = Objects.requireNonNull(runtimeScheduler, "runtimeScheduler");
    }

    void dispatch(final Runnable completionAction) {
        synchronized (quiescenceMonitor) {
            if (closed) {
                throw new IllegalStateException(
                    "Plugin task completion dispatcher is already closed."
                );
            }
            pending.incrementAndGet();
        }
        attempt(new Settlement(completionAction));
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

    private void awaitQuiescence(
        final Duration timeout,
        final boolean closeAfterWait
    ) {
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

    int pendingCount() {
        return pending.get();
    }

    private void attempt(final Settlement settlement) {
        if (settlement.started.get()) {
            return;
        }
        final CallbackSubmission submission = runtimeScheduler.submitCompletion(
            pluginId,
            settlement::run
        );
        if (!submission.accepted()) {
            retry(settlement);
            return;
        }
        submission.completion().whenComplete((result, failure) -> {
            if (settlement.started.get()) {
                return;
            }
            if (failure != null
                || result == null
                || result.status() != CallbackExecutionStatus.SUCCEEDED) {
                retry(settlement);
            }
        });
    }

    private void retry(final Settlement settlement) {
        if (settlement.started.get()) {
            return;
        }
        final var timer = runtimeScheduler.schedule(
            RETRY_DELAY,
            () -> attempt(settlement)
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

    private void finished() {
        if (pending.decrementAndGet() == 0) {
            synchronized (quiescenceMonitor) {
                quiescenceMonitor.notifyAll();
            }
        }
    }

    private final class Settlement {
        private final Runnable action;
        private final AtomicBoolean started = new AtomicBoolean(false);

        private Settlement(final Runnable action) {
            this.action = Objects.requireNonNull(action, "action");
        }

        private void run() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                action.run();
            } finally {
                finished();
            }
        }
    }
}
