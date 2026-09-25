package dev.turboism.adapter.cubism.editor;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.function.Supplier;

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
            throw new IllegalStateException(
                operation + " must run on the Cubism Editor host thread (Swing EDT)."
            );
        }
    }

    /**
     * Runs one task on the host thread, inline when already on it and via
     * {@link SwingUtilities#invokeAndWait} otherwise. Runtime failures propagate unchanged.
     *
     * @param label diagnostic label used when the dispatch itself fails
     * @param task host-thread work
     * @param <T> task result type
     * @return the task result
     */
    public static <T> T dispatch(final String label, final Supplier<T> task) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(task, "task");
        if (SwingUtilities.isEventDispatchThread()) return task.get();
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = task.get();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + " EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException(label + " EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) throw exception;
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] != null) {
            throw new IllegalStateException(label + " EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    private EditorHostThread() {
    }
}
