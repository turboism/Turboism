package dev.turboism.ui.host;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded synchronous dispatch to the host's AWT event dispatch thread.
 *
 * <p>Two entry points share the same contract split:</p>
 * <ul>
 *   <li>{@link #call} — install/query/mutation work whose result the caller needs. If the EDT
 *       does not <em>start</em> the task within the acceptance bound, the queued task is marked
 *       abandoned and its body never runs; the caller receives an {@link EdtDispatchException}.
 *       Once the task has started it is always awaited to completion — {@code call} only returns
 *       early when it can guarantee the body will not execute, so a completed host mutation can
 *       never lose its handle/registration.</li>
 *   <li>{@link #runEventually} — idempotent removal/cleanup work. On acceptance timeout (or while
 *       the EDT is known unresponsive, or the JVM is exiting) the task stays queued and runs when
 *       the EDT drains — exactly once — while the caller returns immediately with a diagnostic.
 *       If the task starts within the bound the caller still waits for completion, so the happy
 *       path keeps synchronous semantics and failure propagation.</li>
 * </ul>
 *
 * <p>Interruption: while still queued, an interrupt abandons a {@code call} (typed failure) or
 * returns a {@code runEventually} (task left queued); after the task has started the interrupt is
 * deferred until completion and then restored. {@link #call} variants carrying an abandon
 * compensation schedule it on the EDT instead — used by modal dialogs whose nested event pump
 * dispatches the compensation (e.g. {@code dialog.dispose()}) to release the caller.</p>
 *
 * <p>An acceptance timeout trips a circuit breaker: a probe runnable is queued behind the wedged
 * work, and until it executes all subsequent dispatches use {@link #UNRESPONSIVE_ACCEPT_TIMEOUT}
 * ({@code runEventually} returns without waiting). A JVM shutdown hook switches to the same short
 * bound so exit-time UI cleanup cannot stall process teardown.</p>
 */
public final class EdtDispatch {

    /** Acceptance bound while the EDT is believed healthy. */
    public static final Duration DEFAULT_ACCEPT_TIMEOUT = Duration.ofSeconds(30);
    /** Acceptance bound while the EDT is suspected unresponsive or the JVM is exiting. */
    public static final Duration UNRESPONSIVE_ACCEPT_TIMEOUT = Duration.ofSeconds(1);

    static final int QUEUED = 0;
    static final int RUNNING = 1;
    static final int DONE = 2;
    static final int ABANDONED = 3;

    private static final String COMPONENT = "host-edt-dispatch";
    private static final long COMPLETION_TICK_MILLIS = 250;
    private static final long SLOW_COMPLETION_WARN_MILLIS = 60_000;

    private static final AtomicBoolean EDT_UNRESPONSIVE = new AtomicBoolean();
    private static final AtomicBoolean EXITING = new AtomicBoolean();

    static {
        try {
            Runtime.getRuntime().addShutdownHook(
                new Thread(() -> EXITING.set(true), "turboism-edt-exit")
            );
        } catch (IllegalStateException alreadyExiting) {
            EXITING.set(true);
        } catch (RuntimeException unavailable) {
            // Shutdown hooks unavailable (e.g. restricted test harness): keep normal bounds.
        }
    }

    private EdtDispatch() {
    }

    /**
     * Runs {@code task} inline when called on the EDT; otherwise queues it and waits up to
     * {@link #DEFAULT_ACCEPT_TIMEOUT} for the EDT to start it.
     *
     * @param label diagnostic label carried by failures and log records
     * @param task work to execute on the EDT
     * @return the task's result
     * @throws EdtDispatchException when the task never started (acceptance timeout/interrupt)
     */
    public static <T> T call(final String label, final Callable<T> task) {
        return call(label, DEFAULT_ACCEPT_TIMEOUT, task, null);
    }

    /** {@link #call(String, Callable)} with an explicit acceptance bound. */
    public static <T> T call(
        final String label,
        final Duration acceptTimeout,
        final Callable<T> task
    ) {
        return call(label, acceptTimeout, task, null);
    }

    /**
     * {@link #call(String, Duration, Callable)} plus an abandon compensation: when the caller is
     * interrupted after the task started, {@code abandonCompensation} is scheduled on the EDT
     * (where a modal dialog's nested pump will run it, e.g. {@code dialog.dispose()}), completion
     * is still awaited, and the caller finally receives an {@link EdtDispatchException} with
     * {@link EdtDispatchException.Reason#INTERRUPTED}. The compensation must be idempotent and
     * safe to run on the EDT.
     */
    public static <T> T call(
        final String label,
        final Duration acceptTimeout,
        final Callable<T> task,
        final Runnable abandonCompensation
    ) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(acceptTimeout, "acceptTimeout");
        if (SwingUtilities.isEventDispatchThread()) {
            return runInline(label, task);
        }
        final Queued<T> queued = new Queued<>(label, task);
        SwingUtilities.invokeLater(queued);
        final boolean shortBound = EXITING.get() || EDT_UNRESPONSIVE.get();
        final Duration bound = shortBound ? UNRESPONSIVE_ACCEPT_TIMEOUT : acceptTimeout;
        boolean interrupted = false;
        try {
            if (!queued.awaitStart(bound)) {
                if (tryAbandon(queued.state)) {
                    markEdtUnresponsive(label);
                    throw new EdtDispatchException(
                        shortBound
                            ? EdtDispatchException.Reason.EDT_UNRESPONSIVE
                            : EdtDispatchException.Reason.ACCEPT_TIMEOUT,
                        label,
                        label + " was not accepted by the EDT within " + bound.toMillis() + "ms"
                    );
                }
                // Lost the CAS: the task is RUNNING (or DONE) — fall through to completion.
            }
        } catch (InterruptedException interruptedWait) {
            if (tryAbandon(queued.state)) {
                Thread.currentThread().interrupt();
                throw new EdtDispatchException(
                    EdtDispatchException.Reason.INTERRUPTED,
                    label,
                    label + " was interrupted while queued for the EDT"
                );
            }
            // The task started concurrently; defer the interrupt until it completes.
            interrupted = true;
        }
        return awaitCompletion(label, queued, abandonCompensation, interrupted);
    }

    /**
     * Queues idempotent cleanup {@code task} on the EDT. When the task starts within the
     * acceptance bound the caller waits for completion (same failure propagation as
     * {@link #call}); on acceptance timeout, in unresponsive mode, or while the JVM is exiting,
     * the caller returns immediately and the queued task still runs exactly once when the EDT
     * drains — deferred failures are logged, never dropped silently.
     */
    public static void runEventually(final String label, final Runnable task) {
        runEventually(label, DEFAULT_ACCEPT_TIMEOUT, task);
    }

    /** {@link #runEventually(String, Runnable)} with an explicit acceptance bound. */
    public static void runEventually(
        final String label,
        final Duration acceptTimeout,
        final Runnable task
    ) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(acceptTimeout, "acceptTimeout");
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        final Queued<Void> queued = new Queued<>(label, () -> {
            task.run();
            return null;
        });
        SwingUtilities.invokeLater(queued);
        if (EXITING.get() || EDT_UNRESPONSIVE.get()) {
            queued.callerGone.set(true);
            RuntimeDiagnostics.debug(
                COMPONENT,
                label + " deferred while the EDT is unresponsive or the JVM is exiting"
            );
            return;
        }
        try {
            if (!queued.awaitStart(acceptTimeout)) {
                markEdtUnresponsive(label);
                queued.callerGone.set(true);
                RuntimeDiagnostics.debug(
                    COMPONENT,
                    label + " deferred: EDT did not accept within " + acceptTimeout.toMillis() + "ms"
                );
                return;
            }
        } catch (InterruptedException interruptedWait) {
            // The caller's lane must not stall; the cleanup still runs when the EDT drains.
            queued.callerGone.set(true);
            Thread.currentThread().interrupt();
            return;
        }
        awaitCompletion(label, queued, null, false);
    }

    /**
     * QUEUED -&gt; ABANDONED transition used to skip a queued task body. Package-private so the
     * state machine can be tested deterministically without timing races.
     */
    static boolean tryAbandon(final AtomicInteger state) {
        return state.compareAndSet(QUEUED, ABANDONED);
    }

    static boolean edtUnresponsiveForTesting() {
        return EDT_UNRESPONSIVE.get();
    }

    static void enterExitModeForTesting() {
        EXITING.set(true);
    }

    static void resetStateForTesting() {
        EDT_UNRESPONSIVE.set(false);
        EXITING.set(false);
    }

    private static <T> T runInline(final String label, final Callable<T> task) {
        try {
            return task.call();
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception checked) {
            throw new IllegalStateException(label + " host EDT operation failed", checked);
        }
    }

    private static <T> T awaitCompletion(
        final String label,
        final Queued<T> queued,
        final Runnable abandonCompensation,
        final boolean alreadyInterrupted
    ) {
        boolean interrupted = alreadyInterrupted;
        boolean compensated = false;
        boolean warned = false;
        final long waitStart = System.nanoTime();
        while (true) {
            try {
                if (queued.awaitDone(COMPLETION_TICK_MILLIS)) {
                    break;
                }
            } catch (InterruptedException interruptedWait) {
                interrupted = true;
                if (abandonCompensation != null && !compensated) {
                    compensated = true;
                    try {
                        SwingUtilities.invokeLater(abandonCompensation);
                    } catch (RuntimeException rejected) {
                        RuntimeDiagnostics.warn(
                            COMPONENT,
                            label + " abandon compensation could not be scheduled"
                        );
                    }
                }
            }
            if (!warned
                && System.nanoTime() - waitStart
                    >= TimeUnit.MILLISECONDS.toNanos(SLOW_COMPLETION_WARN_MILLIS)) {
                warned = true;
                RuntimeDiagnostics.warn(
                    COMPONENT,
                    label + " is still running on the EDT after " + SLOW_COMPLETION_WARN_MILLIS + "ms"
                );
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (interrupted && abandonCompensation != null) {
            throw new EdtDispatchException(
                EdtDispatchException.Reason.INTERRUPTED,
                label,
                label + " was abandoned by interrupt after starting on the EDT"
            );
        }
        return queued.outcome(label);
    }

    private static void markEdtUnresponsive(final String label) {
        if (!EDT_UNRESPONSIVE.compareAndSet(false, true)) {
            return;
        }
        RuntimeDiagnostics.warn(
            COMPONENT,
            label + " timed out waiting for EDT acceptance; subsequent dispatches use a "
                + UNRESPONSIVE_ACCEPT_TIMEOUT.toMillis() + "ms bound until a probe completes"
        );
        try {
            SwingUtilities.invokeLater(() -> {
                EDT_UNRESPONSIVE.set(false);
                RuntimeDiagnostics.debug(
                    COMPONENT,
                    "EDT responsiveness probe completed; normal dispatch bounds restored"
                );
            });
        } catch (RuntimeException rejected) {
            // The probe could not even be queued; let the next timeout re-trip the circuit.
            EDT_UNRESPONSIVE.set(false);
        }
    }

    private static final class Queued<T> implements Runnable {

        private final String label;
        private final Callable<T> task;
        private final AtomicInteger state = new AtomicInteger(QUEUED);
        private final AtomicBoolean callerGone = new AtomicBoolean();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<T> result = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private Queued(final String label, final Callable<T> task) {
            this.label = label;
            this.task = task;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) {
                RuntimeDiagnostics.debug(
                    COMPONENT,
                    label + " skipped on the EDT: dispatch was abandoned"
                );
                done.countDown();
                return;
            }
            started.countDown();
            try {
                result.set(task.call());
            } catch (Throwable throwable) {
                FatalErrors.rethrowIfFatal(throwable);
                failure.set(throwable);
            } finally {
                state.set(DONE);
                done.countDown();
            }
            final Throwable failed = failure.get();
            if (failed != null && callerGone.get()) {
                RuntimeDiagnostics.error(
                    COMPONENT,
                    label + " deferred EDT task failed safely",
                    failed
                );
            }
        }

        private boolean awaitStart(final Duration bound) throws InterruptedException {
            final boolean startedNow = started.await(bound.toNanos(), TimeUnit.NANOSECONDS);
            return startedNow || state.get() == RUNNING || state.get() == DONE;
        }

        private boolean awaitDone(final long tickMillis) throws InterruptedException {
            return done.await(tickMillis, TimeUnit.MILLISECONDS);
        }

        private T outcome(final String label) {
            final Throwable failed = failure.get();
            if (failed instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failed instanceof Error error) {
                throw error;
            }
            if (failed != null) {
                throw new IllegalStateException(label + " host EDT operation failed", failed);
            }
            return result.get();
        }
    }
}
