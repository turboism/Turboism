package dev.turboism.validation.atlasimage.shadow;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Fixed-scene asynchronous EDT gate; it is not a general automation service. */
final class FixedEdt {
    enum Operation {
        MAIN_LOOKUP,
        MENU_DUMP,
        EXPORT_PROBE,
        EXPORT_LOOKUP,
        EDITOR_MENU_DISPATCH,
        EDITOR_LOOKUP,
        LAYOUT_DIALOG_OPEN,
        LAYOUT_LOOKUP,
        LAYOUT_DIALOG_APPLY,
        OK_BUTTON,
        EDITOR_CLOSE_POLL,
        NATIVE_EXIT
    }

    enum State {
        QUEUED,
        STARTED,
        COMPLETED,
        TIMED_OUT
    }

    static final class Invocation<T> {
        private final Callable<T> action;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
        private final AtomicReference<T> value = new AtomicReference<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        Invocation(final Callable<T> action) {
            if (action == null) throw new IllegalArgumentException("EDT action is required");
            this.action = action;
        }

        boolean tryStart() {
            return state.compareAndSet(State.QUEUED, State.STARTED);
        }

        void executeStarted() {
            try {
                value.set(action.call());
            } catch (Throwable caught) {
                failure.compareAndSet(null, caught);
            }
        }

        void signalStarted() {
            started.countDown();
        }

        void completeStarted() {
            state.compareAndSet(State.STARTED, State.COMPLETED);
            started.countDown();
            completed.countDown();
        }

        void skipAfterTimeout() {
            started.countDown();
            completed.countDown();
        }

        void failBeforeDispatch(final Throwable caught) {
            failure.compareAndSet(null, caught);
            state.compareAndSet(State.QUEUED, State.COMPLETED);
            started.countDown();
            completed.countDown();
        }

        boolean awaitStarted(final long seconds) throws InterruptedException {
            return started.await(seconds, TimeUnit.SECONDS);
        }

        boolean awaitCompleted(final long seconds) throws InterruptedException {
            return completed.await(seconds, TimeUnit.SECONDS);
        }

        boolean awaitCompletedMillis(final long millis) throws InterruptedException {
            return completed.await(Math.max(1L, millis), TimeUnit.MILLISECONDS);
        }

        State timeoutIfQueued() {
            if (state.compareAndSet(State.QUEUED, State.TIMED_OUT)) return State.TIMED_OUT;
            return state.get();
        }

        State state() {
            return state.get();
        }

        T value() {
            return value.get();
        }

        Throwable failure() {
            return failure.get();
        }
    }

    static final class Timeout extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final Operation operation;
        final State state;

        Timeout(final Operation operation, final State state) {
            super("EDT operation timeout: " + operation + " state=" + state);
            this.operation = operation;
            this.state = state;
        }
    }

    private FixedEdt() {}

    /** Read-only query: a bounded start wait plus a bounded post-start run. */
    static <T> T call(final Callable<T> action, final Operation operation,
                      final StageEvidence evidence) throws Exception {
        return callWithin(action, operation, evidence,
            TimeUnit.SECONDS.toMillis(ShadowSceneContract.EDT_QUERY_TIMEOUT_SECONDS));
    }

    /**
     * Read-only query with an explicit post-start budget.
     *
     * <p>Queue wait and action duration are separate budgets. The real host monopolizes the
     * EDT with its own startup for tens of seconds; that contention is host state, not
     * fixed-scene action duration, and must not be charged to the action. A started
     * callback is not cancellable, so a caller that owns a long host action passes its own
     * remaining run budget instead of the fixed query budget.</p>
     */
    static <T> T callWithin(final Callable<T> action, final Operation operation,
                            final StageEvidence evidence, final long completionMillis)
                            throws Exception {
        final long startedNanos = System.nanoTime();
        if (evidence != null) evidence.onEdtQueued(operation);
        if (SwingUtilities.isEventDispatchThread()) {
            if (evidence != null) evidence.onEdtStarted(operation);
            try {
                final T value = action.call();
                if (evidence != null) {
                    evidence.onEdtEnded(operation, State.COMPLETED, "COMPLETED",
                        elapsedMillis(startedNanos));
                }
                return value;
            } catch (Throwable caught) {
                if (evidence != null) {
                    evidence.onEdtEnded(operation, State.COMPLETED, "EXCEPTION",
                        elapsedMillis(startedNanos));
                }
                throwFailure(caught, operation.name());
                return null;
            }
        }

        final Invocation<T> invocation = new Invocation<>(action);
        post(invocation, operation, evidence, startedNanos);
        awaitStarted(invocation, operation, evidence);
        return awaitCompleted(invocation, operation, evidence, completionMillis);
    }

    /** Post one callback without waiting; the caller owns both barriers. */
    static <T> void post(final Invocation<T> invocation, final Operation operation,
                         final StageEvidence evidence, final long startedNanos) {
        try {
            SwingUtilities.invokeLater(() -> {
                if (!invocation.tryStart()) {
                    if (evidence != null) evidence.onEdtLateSkipped(operation);
                    invocation.skipAfterTimeout();
                    return;
                }
                if (evidence != null) evidence.onEdtStarted(operation);
                invocation.signalStarted();
                try {
                    invocation.executeStarted();
                    final Throwable caught = invocation.failure();
                    if (evidence != null) {
                        evidence.onEdtEnded(operation, State.COMPLETED,
                            caught == null ? "COMPLETED" : "EXCEPTION",
                            elapsedMillis(startedNanos));
                    }
                } finally {
                    invocation.completeStarted();
                }
            });
        } catch (Throwable caught) {
            invocation.failBeforeDispatch(caught);
            if (evidence != null) {
                evidence.onEdtEnded(operation, State.COMPLETED, "EXCEPTION",
                    elapsedMillis(startedNanos));
            }
        }
    }

    /** Wait only for a queued callback to start; a queued timeout is host EDT contention. */
    static <T> void awaitStarted(final Invocation<T> invocation, final Operation operation,
                                 final StageEvidence evidence) throws Exception {
        try {
            if (!invocation.awaitStarted(ShadowSceneContract.EDT_START_TIMEOUT_SECONDS)) {
                final State state = invocation.timeoutIfQueued();
                if (evidence != null) evidence.onEdtWaitTimedOut(operation, state);
                throw new Timeout(operation, state);
            }
        } catch (InterruptedException interrupted) {
            final State state = invocation.timeoutIfQueued();
            if (evidence != null) evidence.onEdtWaitInterrupted(operation, state);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EDT operation interrupted: " + operation
                + " state=" + state, interrupted);
        }
    }

    /** Wait for a started callback to finish inside the caller's own budget. */
    static <T> T awaitCompleted(final Invocation<T> invocation, final Operation operation,
                               final StageEvidence evidence, final long completionMillis)
                               throws Exception {
        try {
            if (!invocation.awaitCompletedMillis(completionMillis)) {
                final State state = invocation.timeoutIfQueued();
                if (evidence != null) evidence.onEdtWaitTimedOut(operation, state);
                throw new Timeout(operation, state);
            }
        } catch (InterruptedException interrupted) {
            final State state = invocation.timeoutIfQueued();
            if (evidence != null) evidence.onEdtWaitInterrupted(operation, state);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("EDT operation interrupted: " + operation
                + " state=" + state, interrupted);
        }
        throwFailure(invocation.failure(), operation.name());
        return invocation.value();
    }

    private static long elapsedMillis(final long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedNanos));
    }

    static void throwFailure(final Throwable caught, final String description) throws Exception {
        if (caught == null) return;
        if (caught instanceof Exception exception) throw exception;
        if (caught instanceof Error error) throw error;
        throw new IllegalStateException(description + " failed", caught);
    }
}
