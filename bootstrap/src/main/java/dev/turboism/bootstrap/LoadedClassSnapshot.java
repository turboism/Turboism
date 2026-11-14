package dev.turboism.bootstrap;

import java.lang.instrument.Instrumentation;

/**
 * The loaded-class snapshot one install phase shares between its hooks.
 *
 * <p>The provider samples lazily: the first hook of a phase that needs the loaded classes pays the
 * one {@link Instrumentation#getAllLoadedClasses()} enumeration, and every later hook of the same
 * phase reuses it. {@link #invalidateUnlessContains(Iterable)} drops the sample when a hook defines
 * a class the sample cannot answer for, so the next reader observes it.</p>
 *
 * <p>A provider is bound to one {@link HookContributor.Phase} pass: the agent creates a new one for
 * every phase, so a snapshot can never leak across {@code PREMAIN}, {@code HOST_RESOLVED} and
 * {@code RUNTIME_STARTED}.</p>
 */
final class LoadedClassSnapshot {

    private final Instrumentation instrumentation;
    private LoadedClassIndex captured;

    /**
     * @param instrumentation the instrumentation to enumerate through, or {@code null} for an
     *     environment that never reads the loaded classes, such as an admission-only test fixture
     */
    LoadedClassSnapshot(final Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /**
     * Returns the snapshot of this phase, sampling the JVM on first use.
     *
     * @return the shared snapshot for this phase
     * @throws IllegalStateException when this provider belongs to an environment without
     *     instrumentation
     */
    synchronized LoadedClassIndex current() {
        if (captured == null) {
            if (instrumentation == null) {
                throw new IllegalStateException("loaded-class snapshot requires instrumentation");
            }
            captured = LoadedClassIndex.snapshot(instrumentation);
        }
        return captured;
    }

    /**
     * Drops the sample only when it cannot answer for every class in {@code types}.
     *
     * @param types the classes the calling hook defined or loaded
     */
    synchronized void invalidateUnlessContains(final Iterable<Class<?>> types) {
        if (captured == null) return;
        for (final Class<?> type : types) {
            if (type != null && !captured.containsName(type.getName(), type.getClassLoader())) {
                captured = null;
                return;
            }
        }
    }
}
