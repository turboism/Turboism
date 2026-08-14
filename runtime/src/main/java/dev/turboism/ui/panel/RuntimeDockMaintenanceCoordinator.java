package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Routes bounded dock maintenance to the current exact-version host provider. */
public final class RuntimeDockMaintenanceCoordinator {

    private long generation = Long.MIN_VALUE;
    private EmptyDockCleaner cleaner;

    public synchronized Registration bind(final long hostGeneration, final EmptyDockCleaner target) {
        final EmptyDockCleaner requested = Objects.requireNonNull(target, "target");
        // A later panel provider batch replaces the previous maintenance target.
        generation = hostGeneration;
        cleaner = requested;
        return () -> unbind(hostGeneration, requested);
    }

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
