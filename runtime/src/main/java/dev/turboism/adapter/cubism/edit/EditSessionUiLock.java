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
     * Engages the lock: disables the main window when one is available, installs the invisible
     * modal input interceptor, and either shows the status dialog immediately (non-silent) or
     * arms the forced-reveal timer ({@code silent = true}).
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
