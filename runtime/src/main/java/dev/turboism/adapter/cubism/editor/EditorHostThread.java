package dev.turboism.adapter.cubism.editor;

import dev.turboism.ui.host.EdtDispatch;
import java.util.Objects;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Synchronous access to Cubism Editor state on the Swing host thread. */
public final class EditorHostThread {

    /**
     * Whether the calling thread is the Cubism Editor host thread (Swing EDT).
     *
     * @return true on the Swing event dispatch thread
     */
    public static boolean isCurrent() {
        return SwingUtilities.isEventDispatchThread();
    }

    /**
     * Asserts the caller is on the Cubism Editor host thread (Swing EDT). Write envelopes invoke
     * this before opening a native edit so a missed dispatch fails fast instead of mutating host
     * state on a foreign thread.
     */
    public static void requireHostThread(final String operation) {
        if (!isCurrent()) {
            throw new IllegalStateException(operation + " must run on the Cubism Editor host thread (Swing EDT).");
        }
    }

    /**
     * Runs one task on the host thread, inline when already on it and through the bounded
     * {@link EdtDispatch#call} handoff otherwise. Runtime failures propagate unchanged; when
     * the dispatch itself fails (the caller was interrupted or the EDT did not accept the task
     * in time) the queued body is abandoned before it can run, so a reported dispatch failure
     * can never mask a mutation that still executes later.
     *
     * @param label diagnostic label used when the dispatch itself fails
     * @param task host-thread work
     * @param <T> task result type
     * @return the task result
     */
    public static <T> T dispatch(final String label, final Supplier<T> task) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(task, "task");
        return EdtDispatch.call(label, task::get);
    }

    private EditorHostThread() {}
}
