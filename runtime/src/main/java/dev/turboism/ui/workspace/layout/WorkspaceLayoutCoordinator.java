package dev.turboism.ui.workspace.layout;

import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.Optional;

/**
 * Serializes the layout host read against connect/disconnect/close and the AWT EDT, mirroring
 * {@link dev.turboism.ui.workspace.WorkspaceCoordinator}. A read always resolves the current
 * provider under the monitor at the EDT execution point, so a provider replaced or
 * disconnected during the read can never hand back a stale snapshot.
 */
public final class WorkspaceLayoutCoordinator implements AutoCloseable {

    private static final WorkspaceLayoutSnapshot UNAVAILABLE = new WorkspaceLayoutSnapshot(
        WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
        Optional.empty(),
        Optional.of("workspace.layout.provider.unavailable")
    );

    private final Object monitor = new Object();
    private WorkspaceLayoutHostProvider provider;
    private boolean closed;

    public void connect(final WorkspaceLayoutHostProvider value) {
        synchronized (monitor) {
            requireOpen();
            provider = Objects.requireNonNull(value, "provider");
        }
    }

    public void disconnect(final WorkspaceLayoutHostProvider value) {
        synchronized (monitor) {
            if (provider == value) {
                provider = null;
            }
        }
    }

    /**
     * Provider identity selection, the host read, and the snapshot are serialized against
     * connect/disconnect/close by holding the monitor at the EDT execution point. The monitor
     * is never held while dispatching to or waiting for the EDT, so a caller thread blocked in
     * {@link #dispatchOnEdt} cannot deadlock a concurrent disconnect.
     */
    public WorkspaceLayoutSnapshot current() {
        try {
            return dispatchOnEdt(() -> {
                synchronized (monitor) {
                    if (closed) {
                        return UNAVAILABLE;
                    }
                    final WorkspaceLayoutHostProvider active = provider;
                    if (active == null) {
                        return UNAVAILABLE;
                    }
                    final WorkspaceLayoutSnapshot snapshot = active.readLayout();
                    // A read that reentrantly replaces/disconnects the provider must not
                    // return stale state.
                    return provider == active && !closed ? snapshot : UNAVAILABLE;
                }
            });
        } catch (RuntimeException exception) {
            return UNAVAILABLE;
        }
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            provider = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Workspace layout coordinator is closed");
        }
    }

    /** Runs the task on the AWT EDT and rethrows its failures on the caller thread. */
    static <T> T dispatchOnEdt(final Task<T> task) {
        if (SwingUtilities.isEventDispatchThread()) {
            return task.run();
        }
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result[0] = task.run();
                } catch (Throwable throwable) {
                    failure[0] = throwable;
                }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("workspace layout EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("workspace layout EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("workspace layout EDT operation failed", failure[0]);
        }
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    @FunctionalInterface
    interface Task<T> {
        T run();
    }
}
