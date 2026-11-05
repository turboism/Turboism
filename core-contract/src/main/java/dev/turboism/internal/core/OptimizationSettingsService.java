package dev.turboism.internal.core;

/**
 * Runtime-owned preferences for the declared native optimizations, exposed only to the framework
 * shell.
 *
 * <p>One keyed service replaces the per-optimization accessors the shell used to need: the runtime
 * declares every optimization once in its catalogue, so the settings page asks for a preference by
 * the {@code config.json} key that catalogue row declares instead of calling a dedicated method
 * per toggle. Keys stay {@link String}s here on purpose — the internal contract must not depend on
 * the runtime catalogue that owns them.</p>
 *
 * <p>The value is read during JVM startup, so a change applies from the next Cubism launch.</p>
 */
public interface OptimizationSettingsService {

    /**
     * @param key the catalogue key of one native optimization
     * @return the persisted preference, or {@code false} when nothing is persisted or the stored
     *         file cannot be read (fail closed); {@code false} when the runtime cannot persist
     */
    default boolean enabled(final String key) {
        return false;
    }

    /**
     * Persists one preference.
     *
     * @param key the catalogue key of one native optimization
     * @param enabled the value to persist
     * @throws IllegalStateException when the preference cannot be stored, so a failure is never
     *         reported as a successful change
     */
    default void setEnabled(final String key, final boolean enabled) {
        throw new IllegalStateException("optimization settings are unavailable");
    }

    /** @return whether this runtime can persist optimization preferences at all */
    default boolean isAvailable() {
        return true;
    }

    /**
     * @return a service that reports every optimization as not persisted and refuses
     *         {@link #setEnabled(String, boolean)} with {@link IllegalStateException}, for runtimes
     *         that cannot store this preference
     */
    static OptimizationSettingsService unavailable() {
        return new OptimizationSettingsService() {
            @Override
            public boolean enabled(final String key) {
                return false;
            }

            @Override
            public void setEnabled(final String key, final boolean enabled) {
                throw new IllegalStateException("optimization settings are unavailable");
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
