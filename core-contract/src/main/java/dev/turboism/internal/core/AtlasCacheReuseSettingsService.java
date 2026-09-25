package dev.turboism.internal.core;

/**
 * Runtime-owned preference for the atlas cache-reuse guard, exposed only to the built-in
 * Core plugin.
 *
 * <p>The host rebuilds every atlas page on each explicit {@code updateTexture(true, …)} —
 * including export, which recomposites pages the editor session already produced unchanged.
 * Enabling this preference lets the runtime skip a rebuild whose complete draw inputs
 * (page size, ordered tile transforms, per-image version counters, and the filtered-image
 * pixels themselves) match the ones the installed cache was built from; disabling it
 * leaves the host class exactly as the vendor shipped it.</p>
 *
 * <p>The value is read during JVM startup, so a change applies from the next Cubism launch.</p>
 */
public interface AtlasCacheReuseSettingsService {

    /** Default when nothing is persisted: the optimization is on. */
    boolean DEFAULT_ENABLED = true;

    /** @return the persisted preference, or {@link #DEFAULT_ENABLED} when unset */
    boolean read();

    /** Persists the preference; a failure must surface as an exception, never as a silent success. */
    boolean save(boolean value);

    /**
     * @return a service reporting the default on {@link #read()} and refusing {@link #save(boolean)}
     *         with {@link IllegalStateException}, for runtimes that cannot persist this preference
     */
    static AtlasCacheReuseSettingsService unavailable() {
        return new AtlasCacheReuseSettingsService() {
            @Override
            public boolean read() {
                return DEFAULT_ENABLED;
            }

            @Override
            public boolean save(final boolean value) {
                throw new IllegalStateException("atlas cache-reuse settings are unavailable");
            }
        };
    }
}
