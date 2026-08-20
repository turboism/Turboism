package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Routes bounded dock maintenance to the current exact-version host provider. */
public final class RuntimeDockMaintenanceCoordinator {

    private long generation = Long.MIN_VALUE;
    private EmptyDockCleaner cleaner;

    /**
     * Binds the cleaner for the given host generation, replacing any previous target.
     *
     * @param hostGeneration generation of the panel host this cleaner belongs to
     * @param target the host-specific empty-dock cleanup
     * @return a registration that unbinds; a no-op once a later generation or a different cleaner
     *     has taken the slot
     * @throws NullPointerException if {@code target} is {@code null}
     */
    public synchronized Registration bind(final long hostGeneration, final EmptyDockCleaner target) {
        final EmptyDockCleaner requested = Objects.requireNonNull(target, "target");
        // A later panel provider batch replaces the previous maintenance target.
        generation = hostGeneration;
        cleaner = requested;
        return () -> unbind(hostGeneration, requested);
    }

    /**
     * Runs the bound cleanup. The cleaner is read under the lock but invoked outside it, so host
     * work does not block a concurrent rebind.
     *
     * @throws IllegalStateException if no cleaner is currently bound
     */
    public void cleanEmptyDocks() {
        final EmptyDockCleaner current;
        synchronized (this) {
            current = cleaner;
        }
        if (current == null) {
            throw new IllegalStateException("dock maintenance is unavailable");
        }
        current.clean();
    }

    private synchronized void unbind(final long hostGeneration, final EmptyDockCleaner target) {
        if (generation == hostGeneration && cleaner == target) {
            cleaner = null;
            generation = Long.MIN_VALUE;
        }
    }

    @FunctionalInterface
    public interface EmptyDockCleaner {
        void clean();
    }
}
