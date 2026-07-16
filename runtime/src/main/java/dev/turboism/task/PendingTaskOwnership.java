package dev.turboism.task;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Runtime-private scope ownership acquired before physical task admission. */
final class PendingTaskOwnership implements AutoCloseable {

    private enum State {
        REGISTERED_UNBOUND,
        BOUND,
        DISARMED,
        CLOSED
    }

    private final AtomicReference<State> state = new AtomicReference<>(
        State.REGISTERED_UNBOUND
    );
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
        if (state.get() == State.CLOSED) {
            owned.close();
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
        if (state.compareAndSet(State.REGISTERED_UNBOUND, State.BOUND)) {
            return true;
        }
        if (state.get() == State.CLOSED) {
            cancelUnderCleanup();
        }
        return false;
    }

    boolean disarm() {
        while (true) {
            final State current = state.get();
            if (current == State.DISARMED) {
                closeRegistration();
                return true;
            }
            if (current == State.CLOSED) {
                return false;
            }
            if (state.compareAndSet(current, State.DISARMED)) {
                closeRegistration();
                return true;
            }
        }
    }

    boolean isClosed() {
        return state.get() == State.CLOSED;
    }

    @Override
    public void close() {
        while (true) {
            final State current = state.get();
            if (current == State.CLOSED || current == State.DISARMED) {
                return;
            }
            if (state.compareAndSet(current, State.CLOSED)) {
                cancelUnderCleanup();
                return;
            }
        }
    }

    private void cancelUnderCleanup() {
        final AbstractRuntimeTaskHandle handle = candidate.get();
        if (handle == null || !cleanupAttempted.compareAndSet(false, true)) {
            return;
        }
        beginCleanup.run();
        if (handle.cancel()) {
            recordCancellation.run();
        }
    }

    private void closeRegistration() {
        final Registration owned = registration.get();
        if (owned != null) {
            owned.close();
        }
    }
}
