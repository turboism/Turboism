package dev.turboism.adapter.cubism.edit;

/**
 * The editor-UI lock held for the duration of one edit session — the port of the official
 * main-window disable + invisible modal interceptor + status dialog mechanism.
 *
 * <p>All methods run on the host UI thread inside the session manager's dispatch. Implementations
 * must be idempotent: engaging or disengaging twice is a no-op.</p>
 */
public interface EditSessionUiLock {

    /**
     * Engages the lock: disables the main window when one is available. For
     * {@code silent = true} the official startup pulse runs — the invisible modal interceptor
     * is shown at once and holds input for up to the silent timeout, after which the status
     * dialog is revealed; non-silent sessions show the status dialog immediately and never
     * create the interceptor.
     */
    void engage(boolean silent);

    /** Forwards one log line to the session status dialog. */
    void log(String message);

    /** Forwards a {@code [0.0, 1.0]} progress value to the session status dialog. */
    void progress(double value);

    /**
     * Releases the lock: cancels timers, disposes dialogs, and re-enables the main window.
     * Safe to call repeatedly and after a partial engage.
     */
    void disengage();
}
