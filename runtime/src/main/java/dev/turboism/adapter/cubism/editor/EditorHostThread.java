package dev.turboism.adapter.cubism.editor;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.function.Supplier;

/** Synchronous access to Cubism Editor state on the Swing host thread. */
final class EditorHostThread {

    static boolean isCurrent() {
        return SwingUtilities.isEventDispatchThread();
    }

    static <T> T dispatch(final String label, final Supplier<T> task) {
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
