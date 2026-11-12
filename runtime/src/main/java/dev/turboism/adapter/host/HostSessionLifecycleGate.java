package dev.turboism.adapter.host;

import java.util.Objects;
import java.util.Optional;

/**
 * Admission fence and lifecycle ledger for one host session.
 *
 * <p>Every state change runs under one monitor, so no caller observes a half-applied transition.
 * A close request is monotonic: once recorded it stays recorded, and no later refresh may admit
 * new host work. A transition requested from inside an adapter call is refused, because the
 * deferred close must run only after the outermost adapter call returned.</p>
 */
final class HostSessionLifecycleGate {

    private final Object monitor = new Object();

    private HostSession.State state = HostSession.State.SAFE_MODE;
    private Optional<HostSessionFailure> lastFailure = Optional.empty();
    private boolean transitionInProgress;
    private Thread transitionOwner;
    private boolean closeRequested;

    /** @return the committed lifecycle state, never read mid-transition */
    HostSession.State state() {
        synchronized (monitor) {
            return state;
        }
    }

    /** @return the failure that moved the session off its healthy path; empty until one is recorded */
    Optional<HostSessionFailure> lastFailure() {
        synchronized (monitor) {
            return lastFailure;
        }
    }

    /** @return true once a close was requested; the flag never returns to false */
    boolean closeRequested() {
        synchronized (monitor) {
            return closeRequested;
        }
    }

    /**
     * Claims the single transition slot for the calling thread.
     *
     * @param closing true when the caller intends to close, which also records the close request
     * @param insideAdapterCall true when the caller already serves an adapter call and therefore
     *     must not start a transition
     * @return true when the caller owns the transition and must release it with {@link #endTransition()}
     */
    boolean beginTransition(final boolean closing, final boolean insideAdapterCall) {
        if (insideAdapterCall) {
            if (closing) {
                synchronized (monitor) {
                    closeRequested = true;
                }
            }
            return false;
        }

        synchronized (monitor) {
            if (closing) {
                closeRequested = true;
            }
            if (state == HostSession.State.CLOSED || (!closing && closeRequested)) {
                return false;
            }
            if (transitionInProgress && transitionOwner == Thread.currentThread()) {
                return false;
            }
            if (!closing && transitionInProgress && closeRequested) {
                return false;
            }

            boolean interrupted = false;
            while (transitionInProgress) {
                if (!closing && closeRequested) {
                    return false;
                }
                try {
                    monitor.wait();
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (state == HostSession.State.CLOSED || (!closing && closeRequested)) {
                return false;
            }
            transitionInProgress = true;
            transitionOwner = Thread.currentThread();
            return true;
        }
    }

    /** Releases the transition slot claimed by {@link #beginTransition} and wakes every waiter. */
    void endTransition() {
        synchronized (monitor) {
            transitionInProgress = false;
            transitionOwner = null;
            monitor.notifyAll();
        }
    }

    /**
     * Commits one lifecycle state together with the failure that explains it.
     *
     * @param newState the state every later reader observes
     * @param failure the failure to retain; empty when the state change is not a failure
     * @return the committed state
     */
    HostSession.State commit(final HostSession.State newState, final Optional<HostSessionFailure> failure) {
        synchronized (monitor) {
            state = Objects.requireNonNull(newState, "newState");
            lastFailure = Objects.requireNonNull(failure, "failure");
            return state;
        }
    }

    /** @return true when a close was requested and the session is still open, so close must run */
    boolean closeStillPending() {
        synchronized (monitor) {
            return closeRequested && state != HostSession.State.CLOSED;
        }
    }
}
