package dev.turboism.ui.host;

import java.util.Objects;

/**
 * Typed failure of a bounded synchronous EDT dispatch through {@link EdtDispatch}.
 * Thrown only while the dispatched task's body is guaranteed never to run — a task that
 * has started on the EDT is always awaited to completion and its outcome delivered.
 */
public final class EdtDispatchException extends RuntimeException {

    /** Why the dispatch failed before the task body started. */
    public enum Reason {
        /** The EDT did not start the task within the normal acceptance bound. */
        ACCEPT_TIMEOUT,
        /** The EDT did not start the task within the short unresponsive/exit bound. */
        EDT_UNRESPONSIVE,
        /** The calling thread was interrupted while the task was still queued. */
        INTERRUPTED
    }

    private final Reason reason;
    private final String label;

    EdtDispatchException(final Reason reason, final String label, final String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
        this.label = Objects.requireNonNull(label, "label");
    }

    /** @return why the dispatch failed before the task body started */
    public Reason reason() {
        return reason;
    }

    /** @return the diagnostic label of the rejected dispatch */
    public String label() {
        return label;
    }
}
