package dev.turboism.ui;

import dev.turboism.core.runtime.CancellationContext;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeCancellationToken;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-plugin {@link UiScheduler} that funnels UI work through the runtime scheduler.
 *
 * <p>Work is never run inline on the caller's thread: it is dispatched as a plugin task attributed to
 * this scheduler's plugin id, so it is subject to the runtime's ordering and accounting. Delays are
 * timed on a private single-threaded daemon timer, named after the plugin, and a negative delay is
 * treated as zero.
 *
 * <p>Every schedule returns a {@link Registration} that cancels the pending work; cancelling after the
 * work has started does not interrupt it. {@link #close()} shuts the timer down immediately, so
 * already-delayed work that has not yet been dispatched never runs — it does not cancel work already
 * handed to the runtime scheduler.
 */
public final class RuntimeUiScheduler implements UiScheduler, AutoCloseable {

    private static final String UI_TASK_TYPE = "ui.schedule";
    private static final String DEFAULT_CAPABILITY = "none";

    private final RuntimeScheduler scheduler;
    private final String pluginId;
    private final ScheduledExecutorService timer;

    public RuntimeUiScheduler(RuntimeScheduler scheduler, String pluginId) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pluginId = requireText(pluginId, "pluginId");
        this.timer = Executors.newSingleThreadScheduledExecutor(new UiTimerThreadFactory(pluginId));
    }

    @Override
    public Registration runOnUiThread(Runnable work) {
        Objects.requireNonNull(work, "work");
        AtomicBoolean cancelled = new AtomicBoolean();
        final boolean accepted = scheduler.dispatch(
            task("immediate UI work"),
            () -> dispatchOnEdt(work, cancelled),
            () -> cancelled.set(true)
        );
        if (!accepted) {
            throw new IllegalStateException("UI_SCHEDULER_REJECTED");
        }
        return () -> cancelled.set(true);
    }

    @Override
    public Registration runOnUiThreadLater(Runnable work, Duration delay) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(delay, "delay");
        AtomicBoolean cancelled = new AtomicBoolean();
        ScheduledFuture<?> scheduled = timer.schedule(
            () -> scheduler.dispatch(
                task("delayed UI work"),
                () -> dispatchOnEdt(work, cancelled)
            ),
            Math.max(0L, delay.toMillis()),
            TimeUnit.MILLISECONDS
        );
        return () -> {
            cancelled.set(true);
            scheduled.cancel(false);
        };
    }

    @Override
    public void close() {
        timer.shutdownNow();
    }

    /**
     * Enqueues work on the EDT and waits for that work to settle from the runtime
     * worker. This makes the runtime work budget cover the callback itself rather
     * than merely the enqueue operation.
     *
     * <p>A timeout is cooperative: the queued callback is cancelled before it
     * starts, but a callback already running on the EDT is never interrupted or
     * otherwise preempted.
     */
    private static void dispatchOnEdt(
        final Runnable work,
        final AtomicBoolean cancelled
    ) {
        if (SwingUtilities.isEventDispatchThread()) {
            runGuarded(work, cancelled, CancellationContext.get());
            return;
        }

        RuntimeCancellationToken token = CancellationContext.get();
        EdtCallback callback = new EdtCallback(work, cancelled, token);
        SwingUtilities.invokeLater(callback);
        try {
            callback.awaitCompletion();
        } catch (InterruptedException exception) {
            cancelled.set(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("UI callback was interrupted while awaiting the EDT", exception);
        }
    }

    private static void runGuarded(
        final Runnable work,
        final AtomicBoolean cancelled,
        final RuntimeCancellationToken token
    ) {
        if (cancelled.get()) {
            return;
        }
        if (token != null) {
            CancellationContext.set(token);
        }
        try {
            if (!cancelled.get()) {
                work.run();
            }
        } finally {
            if (token != null) {
                CancellationContext.clear();
            }
        }
    }

    private static final class EdtCallback implements Runnable {

        private final Runnable work;
        private final AtomicBoolean cancelled;
        private final RuntimeCancellationToken token;
        private final CountDownLatch settled = new CountDownLatch(1);
        private volatile Throwable failure;

        private EdtCallback(
            final Runnable work,
            final AtomicBoolean cancelled,
            final RuntimeCancellationToken token
        ) {
            this.work = work;
            this.cancelled = cancelled;
            this.token = token;
        }

        @Override
        public void run() {
            try {
                runGuarded(work, cancelled, token);
            } catch (Throwable exception) {
                failure = exception;
            } finally {
                settled.countDown();
            }
        }

        private void awaitCompletion() throws InterruptedException {
            settled.await();
            if (failure != null) {
                throwUnchecked(failure);
            }
        }

        private static void throwUnchecked(final Throwable failure) {
            EdtCallback.<RuntimeException>throwAny(failure);
        }

        @SuppressWarnings("unchecked")
        private static <T extends Throwable> void throwAny(final Throwable failure) throws T {
            throw (T) failure;
        }
    }

    private PluginTask task(String payloadDescription) {
        return new PluginTask(UI_TASK_TYPE, pluginId, payloadDescription, DEFAULT_CAPABILITY);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record UiTimerThreadFactory(String pluginId) implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "turboism-ui-timer-" + pluginId);
            thread.setDaemon(true);
            return thread;
        }
    }
}
