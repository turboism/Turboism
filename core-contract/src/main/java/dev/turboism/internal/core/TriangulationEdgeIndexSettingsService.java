package dev.turboism.internal.core;

/**
 * Runtime-owned preference for the triangulation edge-index weave, exposed only to the built-in
 * Core plugin.
 *
 * <p>One host class answers every edge-list query by scanning the whole triangle set. Enabling
 * this preference lets the runtime keep a per-set index over unordered endpoint pairs so lookups
 * resolve through insertion-ordered buckets instead of a full scan; disabling it leaves the host
 * class exactly as the vendor shipped it.</p>
 *
 * <p>The value is read during JVM startup, so a change applies from the next Cubism launch.</p>
 */
public interface TriangulationEdgeIndexSettingsService {

    /** Default when nothing is persisted: the weave is on. */
    boolean DEFAULT_ENABLED = true;

    /** @return the persisted preference, or {@link #DEFAULT_ENABLED} when unset */
    boolean read();

    /** Persists the preference; a failure must surface as an exception, never as a silent success. */
    boolean save(boolean value);

    /**
     * @return a service reporting the default on {@link #read()} and refusing {@link #save(boolean)}
     *         with {@link IllegalStateException}, for runtimes that cannot persist this preference
     */
    static TriangulationEdgeIndexSettingsService unavailable() {
        return new TriangulationEdgeIndexSettingsService() {
            @Override
            public boolean read() {
                return DEFAULT_ENABLED;
            }

            @Override
            public boolean save(final boolean value) {
                throw new IllegalStateException("triangulation edge-index settings are unavailable");
            }
        };
    }
}
