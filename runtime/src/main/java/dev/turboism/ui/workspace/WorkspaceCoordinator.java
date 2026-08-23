package dev.turboism.ui.workspace;

import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Serializes all workspace reads and mutations against a single connected
 * {@link WorkspaceHostProvider}, running every host touch on the AWT event
 * dispatch thread.
 *
 * <p>The coordinator fails closed rather than propagating host trouble: a
 * missing provider, a closed coordinator, a provider replaced reentrantly
 * during an operation, or a {@link RuntimeException} out of the host all
 * produce an unavailable or failed result with a stable reason code
 * instead of an exception. State read from a provider that was swapped out
 * mid-operation is never returned.</p>
 */
public final class WorkspaceCoordinator implements AutoCloseable {
    private static final WorkspaceStatus UNAVAILABLE = new WorkspaceStatus(
        WorkspaceStatus.Availability.UNAVAILABLE,
        Optional.empty(),
        List.of(),
        Optional.of("workspace.provider.unavailable")
    );

    private final Object monitor = new Object();
    private WorkspaceHostProvider provider;
    private boolean closed;

    /**
     * Installs the provider all later operations run against, replacing any
     * previously connected one.
     *
     * @param value provider to connect
     * @throws IllegalStateException if the coordinator is already closed
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public void connect(final WorkspaceHostProvider value) {
        synchronized (monitor) {
            requireOpen();
            provider = Objects.requireNonNull(value, "provider");
        }
    }

    /**
     * Removes the given provider if it is still the connected one; a stale
     * provider is ignored, so a late disconnect cannot detach a newer one.
     *
     * @param value provider to detach
     */
    public void disconnect(final WorkspaceHostProvider value) {
        synchronized (monitor) {
            if (provider == value) provider = null;
        }
    }

    /**
     * Provider identity selection, the host operation, and the post-state read are serialized
     * against connect/disconnect/close by holding the monitor at the EDT execution point. The
     * monitor is never held while dispatching to or waiting for the EDT, so a caller thread
     * blocked in {@link #dispatchOnEdt} cannot deadlock a concurrent disconnect.
     */
    public WorkspaceStatus current() {
        try {
            return dispatchOnEdt(() -> {
                synchronized (monitor) {
                    if (closed) return UNAVAILABLE;
                    final WorkspaceHostProvider active = provider;
                    if (active == null) return UNAVAILABLE;
                    final WorkspaceStatus status = active.readStatus();
                    // A read that reentrantly replaces/disconnects the provider must not return
                    // stale state.
                    return provider == active && !closed ? status : UNAVAILABLE;
                }
            });
        } catch (RuntimeException exception) {
            return UNAVAILABLE;
        }
    }

    /**
     * Asks the host to activate a workspace.
     *
     * @param workspaceId workspace to switch to
     * @return the outcome together with the workspace status observed
     *     afterwards; {@code UNAVAILABLE} when no provider is connected, and
     *     {@code FAILED} when the host operation threw or the provider was
     *     replaced mid-operation
     * @throws NullPointerException if {@code workspaceId} is {@code null}
     */
    public WorkspaceOperationResult switchTo(final WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return mutate(provider -> provider.switchTo(workspaceId));
    }

    /**
     * Asks the host to overwrite the default workspace layout with the current
     * one.
     *
     * @return the outcome and the status observed afterwards, failing closed
     *     the same way as {@link #switchTo}
     */
    public WorkspaceOperationResult updateDefault() {
        return mutate(WorkspaceHostProvider::updateDefault);
    }

    /**
     * Asks the host to discard the current layout and restore the default.
     *
     * @return the outcome and the status observed afterwards, failing closed
     *     the same way as {@link #switchTo}
     */
    public WorkspaceOperationResult resetToDefault() {
        return mutate(WorkspaceHostProvider::resetToDefault);
    }

    private WorkspaceOperationResult mutate(final Operation operation) {
        try {
            return dispatchOnEdt(() -> {
                synchronized (monitor) {
                    if (closed) return unavailableResult();
                    final WorkspaceHostProvider active = provider;
                    if (active == null) return unavailableResult();
                    try {
                        final WorkspaceOperationResult.Outcome outcome = operation.run(active);
                        if (provider != active || closed) {
                            // Reentrant replacement during the host operation: fail closed without
                            // reading post-state from the stale provider.
                            return replacedResult();
                        }
                        final WorkspaceStatus status = active.readStatus();
                        if (provider != active || closed) {
                            // The post-state read reentrantly replaced the provider: never return
                            // stale state.
                            return replacedResult();
                        }
                        return new WorkspaceOperationResult(outcome, status, Optional.empty());
                    } catch (RuntimeException exception) {
                        if (provider != active || closed) {
                            // The operation reentrantly replaced the provider before throwing:
                            // never touch or return state from the stale provider.
                            return replacedResult();
                        }
                        return new WorkspaceOperationResult(
                            WorkspaceOperationResult.Outcome.FAILED,
                            safeStatus(active),
                            Optional.of("workspace.operation.failed")
                        );
                    }
                }
            });
        } catch (RuntimeException exception) {
            return new WorkspaceOperationResult(
                WorkspaceOperationResult.Outcome.FAILED,
                UNAVAILABLE,
                Optional.of("workspace.operation.failed")
            );
        }
    }

    private static WorkspaceOperationResult replacedResult() {
        return new WorkspaceOperationResult(
            WorkspaceOperationResult.Outcome.FAILED,
            UNAVAILABLE,
            Optional.of("workspace.provider.replaced")
        );
    }

    private WorkspaceStatus safeStatus(final WorkspaceHostProvider active) {
        try {
            final WorkspaceStatus status = active.readStatus();
            return provider == active && !closed ? status : UNAVAILABLE;
        } catch (RuntimeException ignored) {
            return UNAVAILABLE;
        }
    }


    private static WorkspaceOperationResult unavailableResult() {
        return new WorkspaceOperationResult(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            UNAVAILABLE,
            Optional.of("workspace.provider.unavailable")
        );
    }

    @Override
    public void close() {
        synchronized (monitor) {
            closed = true;
            provider = null;
        }
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Workspace coordinator is closed");
    }

    /** Runs the task on the AWT EDT and rethrows its failures on the caller thread. */
    static <T> T dispatchOnEdt(final Task<T> task) {
        if (SwingUtilities.isEventDispatchThread()) return task.run();
        final Object[] result = new Object[1];
        final Throwable[] failure = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { result[0] = task.run(); }
                catch (Throwable throwable) { failure[0] = throwable; }
            });
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("workspace EDT operation was interrupted", exception);
        } catch (InvocationTargetException exception) {
            throw new IllegalStateException("workspace EDT operation failed", exception);
        }
        if (failure[0] instanceof RuntimeException exception) throw exception;
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] != null) throw new IllegalStateException("workspace EDT operation failed", failure[0]);
        @SuppressWarnings("unchecked") final T value = (T) result[0];
        return value;
    }

    @FunctionalInterface private interface Operation {
        WorkspaceOperationResult.Outcome run(WorkspaceHostProvider provider);
    }
    @FunctionalInterface interface Task<T> { T run(); }
}
