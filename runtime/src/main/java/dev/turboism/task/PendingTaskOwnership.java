package dev.turboism.task;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-private scope ownership acquired before physical task admission. */
final class PendingTaskOwnership implements AutoCloseable {

    private enum State {
        REGISTERED_UNBOUND,
        CLOSE_REQUESTED_UNBOUND,
        BOUND,
        DISARMED,
        CLOSED
    }

    private final AtomicReference<State> state = new AtomicReference<>(
        State.REGISTERED_UNBOUND
    );
    private final CountDownLatch admissionDecided = new CountDownLatch(1);
    private final AtomicReference<AbstractRuntimeTaskHandle> candidate = new AtomicReference<>();
    private final AtomicReference<Registration> registration = new AtomicReference<>();
    private final AtomicBoolean cleanupAttempted = new AtomicBoolean();
    private final Runnable beginCleanup;
    private final Runnable recordCancellation;

    PendingTaskOwnership(
        final Runnable beginCleanup,
        final Runnable recordCancellation
    ) {
        this.beginCleanup = Objects.requireNonNull(beginCleanup, "beginCleanup");
        this.recordCancellation = Objects.requireNonNull(
            recordCancellation,
            "recordCancellation"
        );
    }

    void attachRegistration(final Registration scopedRegistration) {
        final Registration owned = Objects.requireNonNull(
            scopedRegistration,
            "scopedRegistration"
        );
        if (!registration.compareAndSet(null, owned)) {
            throw new IllegalStateException("Task scope ownership is already registered");
        }
        if (state.get() == State.DISARMED) {
            closeRegistration();
        }
    }

    void ownCandidate(final AbstractRuntimeTaskHandle handle) {
        if (!candidate.compareAndSet(null, Objects.requireNonNull(handle, "handle"))) {
            throw new IllegalStateException("Task scope ownership already has a candidate");
        }
        if (state.get() == State.CLOSED) {
            cancelUnderCleanup();
        }
    }

    boolean bind() {
        while (true) {
            final State current = state.get();
            if (current == State.REGISTERED_UNBOUND) {
                if (state.compareAndSet(current, State.BOUND)) {
                    admissionDecided.countDown();
                    return true;
                }
                continue;
            }
            if (current == State.CLOSE_REQUESTED_UNBOUND) {
                if (state.compareAndSet(current, State.CLOSED)) {
                    cancelUnderCleanup();
                    admissionDecided.countDown();
                }
                return false;
            }
            if (current == State.CLOSED) {
                cancelUnderCleanup();
            }
            if (current == State.BOUND || current == State.CLOSED) {
                admissionDecided.countDown();
            }
            return false;
        }
    }

    boolean disarm() {
        while (true) {
            final State current = state.get();
            if (current == State.DISARMED) {
                admissionDecided.countDown();
                return true;
            }
            if (current != State.REGISTERED_UNBOUND
                && current != State.CLOSE_REQUESTED_UNBOUND) {
                return false;
            }
            if (state.compareAndSet(current, State.DISARMED)) {
                releaseReferencesWithoutCleanup();
                admissionDecided.countDown();
                return true;
            }
        }
    }

    void releaseAfterTerminal() {
        if (state.compareAndSet(State.BOUND, State.DISARMED)) {
            releaseReferencesWithoutCleanup();
        }
    }

    void runWhenBound(final Runnable runnable) {
        final Runnable admitted = Objects.requireNonNull(runnable, "runnable");
        boolean interrupted = false;
        while (true) {
            try {
                admissionDecided.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        try {
            if (state.get() == State.BOUND) {
                admitted.run();
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    boolean isCloseRequested() {
        final State current = state.get();
        return current == State.CLOSE_REQUESTED_UNBOUND || current == State.CLOSED;
    }

    @Override
    public void close() {
        while (true) {
            final State current = state.get();
            if (current == State.CLOSE_REQUESTED_UNBOUND
                || current == State.CLOSED
                || current == State.DISARMED) {
                return;
            }
            if (current == State.REGISTERED_UNBOUND) {
                if (state.compareAndSet(current, State.CLOSE_REQUESTED_UNBOUND)) {
                    return;
                }
                continue;
            }
            if (state.compareAndSet(current, State.CLOSED)) {
                cancelUnderCleanup();
                return;
            }
        }
    }

    private void cancelUnderCleanup() {
        final AbstractRuntimeTaskHandle handle = candidate.getAndSet(null);
        registration.set(null);
        if (handle == null || !cleanupAttempted.compareAndSet(false, true)) {
            return;
        }
        beginCleanup.run();
        if (handle.cancel()) {
            recordCancellation.run();
        }
    }

    private void releaseReferencesWithoutCleanup() {
        candidate.set(null);
        closeRegistration();
    }

    private void closeRegistration() {
        final Registration owned = registration.getAndSet(null);
        if (owned != null) {
            owned.close();
        }
    }
}
