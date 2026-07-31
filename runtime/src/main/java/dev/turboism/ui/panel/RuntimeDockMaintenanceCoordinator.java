package dev.turboism.ui.panel;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Routes bounded dock maintenance to the current exact-version host provider. */
public final class RuntimeDockMaintenanceCoordinator {

    private long generation = Long.MIN_VALUE;
    private EmptyDockCleaner cleaner;

    public synchronized Registration bind(final long hostGeneration, final EmptyDockCleaner target) {
        final EmptyDockCleaner requested = Objects.requireNonNull(target, "target");
        if (cleaner != null) {
            throw new IllegalStateException("dock maintenance target is already bound");
        }
        generation = hostGeneration;
        cleaner = requested;
        return () -> unbind(hostGeneration, requested);
    }

    public synchronized void cleanEmptyDocks() {
        if (cleaner == null) {
            throw new IllegalStateException("dock maintenance is unavailable");
        }
        cleaner.clean();
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
