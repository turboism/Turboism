package dev.turboism.preview;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single bounded lane every plugin lifecycle body runs on.
 *
 * <p>A fixed worker pool with a bounded queue executes one plugin's construct/init/enable (or
 * disable/shutdown/unload) sequence per task. The caller awaits each task with a deadline; on
 * timeout the task is interrupted and fenced rather than joined forever, and {@code workerDone}
 * — not the cancelled Future — records when the worker thread actually exits. Saturation and
 * shutdown reject admissions explicitly; lifecycle work never falls back to the caller thread.</p>
 */
final class PluginLifecycleLane {

    private final ThreadPoolExecutor executor;
    private final AtomicInteger workerSequence = new AtomicInteger();
    private final AtomicBoolean accepting = new AtomicBoolean(true);

    PluginLifecycleLane(final PluginLifecyclePolicy policy) {
        Objects.requireNonNull(policy, "policy");
        executor = new ThreadPoolExecutor(
            policy.workerCount(),
            policy.workerCount(),
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(policy.queueCapacity()),
            runnable -> {
                final Thread thread = new Thread(
                    runnable,
                    "turboism-plugin-lifecycle-" + workerSequence.incrementAndGet()
                );
                thread.setDaemon(true);
                thread.setContextClassLoader(PluginLifecycleLane.class.getClassLoader());
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * Admits one lifecycle task.
     *
     * @return the invocation handle; {@link Invocation#rejected} is set when the lane stopped
     *     accepting work or the bounded queue is full — the task never runs in that case
     */
    <T> Invocation<T> submit(
        final String pluginId,
        final String phase,
        final Callable<T> work
    ) {
        final Invocation<T> invocation = new Invocation<>(pluginId, phase);
        if (!accepting.get()) {
            invocation.rejected = true;
            invocation.workerDone.complete(null);
            return invocation;
        }
        return dispatch(invocation, work);
    }

    /**
     * Admits one task on behalf of the retention watcher. Retained cleanup keeps a bounded claim
     * on the lane after public admission stops — its generations are already tracked and still
     * hold plugin resources — so this bypasses the admission flag but not the bounded queue.
     */
    <T> Invocation<T> submitRetained(
        final String pluginId,
        final String phase,
        final Callable<T> work
    ) {
        return dispatch(new Invocation<>(pluginId, phase), work);
    }

    private <T> Invocation<T> dispatch(final Invocation<T> invocation, final Callable<T> work) {
        try {
            invocation.raw = executor.submit(() -> {
                if (!invocation.state.compareAndSet(Invocation.PENDING, Invocation.RUNNING)) {
                    // Cancellation won the handshake before this worker entered the body: plugin
                    // work must not start, but workerDone is still owed to retention waiters.
                    invocation.workerDone.complete(null);
                    return null;
                }
                try {
                    invocation.result.complete(work.call());
                } catch (Throwable failure) {
                    invocation.result.completeExceptionally(failure);
                } finally {
                    invocation.workerDone.complete(null);
                }
                return null;
            });
        } catch (RejectedExecutionException rejected) {
            invocation.rejected = true;
            invocation.workerDone.complete(null);
        }
        return invocation;
    }

    /**
     * Waits for one invocation up to {@code timeout} and resolves the success/timeout race
     * against {@code lease} when given.
     *
     * <p>On timeout the lease is fenced first; if the commit section already won, the outcome is
     * reported as success instead. The worker is then interrupted — advisory only — and the
     * caller must consult {@link Invocation#workerDone} before reclaiming resources.</p>
     */
    <T> AwaitResult<T> await(
        final Invocation<T> invocation,
        final Duration timeout,
        final PluginLifecycleLease lease
    ) {
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(timeout, "timeout");
        if (invocation.rejected) {
            return AwaitResult.rejected();
        }
        try {
            return AwaitResult.succeeded(invocation.result.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException timeoutExpired) {
            if (lease != null && !lease.expire()) {
                // expire() loses only when the commit section already won (COMMITTED); an
                // already-EXPIRED lease — fenced by an earlier timeout on the same generation —
                // still means this invocation timed out.
                if (lease.isCommitted()) {
                    return committedBeforeTimeout(invocation);
                }
            }
            interrupt(invocation);
            return AwaitResult.timedOut();
        } catch (ExecutionException failure) {
            return AwaitResult.failed(failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (lease != null) {
                lease.expire();
            }
            interrupt(invocation);
            return AwaitResult.timedOut();
        }
    }

    /** Stops admitting new work. Running and queued tasks are unaffected. */
    void stopAdmission() {
        accepting.set(false);
    }

    /** Shuts the worker pool down; running tasks are interrupted. */
    void shutdown() {
        accepting.set(false);
        executor.shutdownNow();
    }

    private void interrupt(final Invocation<?> invocation) {
        final Future<?> raw = invocation.raw;
        if (raw == null) {
            return;
        }
        raw.cancel(true);
        // Only cancellation that wins the PENDING handshake may settle workerDone here: a
        // wrapper that already entered RUNNING completes it in its own finally block, and a
        // cancelled wrapper completes it without running plugin work.
        if (invocation.state.compareAndSet(Invocation.PENDING, Invocation.CANCELLED)) {
            invocation.workerDone.complete(null);
        }
    }

    private <T> AwaitResult<T> committedBeforeTimeout(final Invocation<T> invocation) {
        try {
            return AwaitResult.succeeded(invocation.result.get(5, TimeUnit.SECONDS));
        } catch (ExecutionException failure) {
            return AwaitResult.failed(failure.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return AwaitResult.timedOut();
        } catch (TimeoutException impossible) {
            return AwaitResult.failed(new IllegalStateException(
                "Committed lifecycle task did not publish its result"
            ));
        }
    }

    enum Outcome {
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        REJECTED
    }

    static final class AwaitResult<T> {
        final Outcome outcome;
        final T value;
        final Throwable failure;

        private AwaitResult(final Outcome outcome, final T value, final Throwable failure) {
            this.outcome = outcome;
            this.value = value;
            this.failure = failure;
        }

        static <T> AwaitResult<T> succeeded(final T value) {
            return new AwaitResult<>(Outcome.SUCCEEDED, value, null);
        }

        static <T> AwaitResult<T> failed(final Throwable failure) {
            return new AwaitResult<>(Outcome.FAILED, null, failure);
        }

        static <T> AwaitResult<T> timedOut() {
            return new AwaitResult<>(Outcome.TIMED_OUT, null, null);
        }

        static <T> AwaitResult<T> rejected() {
            return new AwaitResult<>(Outcome.REJECTED, null, null);
        }
    }

    /**
     * One admitted (or refused) lifecycle task.
     *
     * <p>{@code result} completes when the task body returns or throws — possibly long after the
     * caller stopped waiting. {@code workerDone} completes only when the worker thread exits the
     * task body, which is the quiescence signal retention waits for; a cancelled future is not
     * evidence the code stopped. {@code workerDone} completes immediately for rejected tasks.</p>
     */
    static final class Invocation<T> {
        static final int PENDING = 0;
        static final int RUNNING = 1;
        static final int CANCELLED = 2;

        final String pluginId;
        final String phase;
        final CompletableFuture<T> result = new CompletableFuture<>();
        final CompletableFuture<Void> workerDone = new CompletableFuture<>();
        /** PENDING → RUNNING is claimed by the wrapper; PENDING → CANCELLED by the canceller. */
        final AtomicInteger state = new AtomicInteger(PENDING);
        volatile Future<?> raw;
        volatile boolean rejected;

        private Invocation(final String pluginId, final String phase) {
            this.pluginId = pluginId;
            this.phase = phase;
        }
    }
}
