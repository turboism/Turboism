package dev.turboism.adapter.cubism.performance;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent-to-runtime seam for the FPS counting hook. The bootstrap agent
 * publishes the single JVM-wide hook handle after host detection; the runtime
 * performance service mounts/unmounts it through the same handle.
 */
public final class PerformanceFpsHookRegistry {

    private static final AtomicReference<PerformanceFpsHook> PUBLISHED = new AtomicReference<>();

    private PerformanceFpsHookRegistry() { }

    /** Publishes the agent-owned hook. Rejects a second publisher. */
    public static void publish(final PerformanceFpsHook hook) {
        Objects.requireNonNull(hook, "hook");
        if (!PUBLISHED.compareAndSet(null, hook)) {
            throw new IllegalStateException("performance FPS hook is already published");
        }
    }

    /** The published hook, or empty when no host is attached. */
    public static Optional<PerformanceFpsHook> installed() {
        return Optional.ofNullable(PUBLISHED.get());
    }

    /** Clears the published handle; only the publisher may clear it. */
    public static void clear(final PerformanceFpsHook hook) {
        PUBLISHED.compareAndSet(Objects.requireNonNull(hook, "hook"), null);
    }
}
