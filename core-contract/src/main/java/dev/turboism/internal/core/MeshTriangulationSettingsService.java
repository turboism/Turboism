package dev.turboism.internal.core;

/**
 * Runtime-owned preference for the mesh triangulation hash fix, exposed only to the built-in Core
 * plugin.
 *
 * <p>One host class hashes its triangle-corner triples to a constant, which collapses the
 * triangulator's edge set into a single bucket and degrades lookups towards linear scanning.
 * Enabling this preference lets the runtime replace that method with a permutation-invariant hash;
 * disabling it leaves the host class exactly as the vendor shipped it.</p>
 *
 * <p>The value is read during JVM startup, so a change applies from the next Cubism launch.</p>
 */
public interface MeshTriangulationSettingsService {

    /** Default when nothing is persisted: the fix is on. */
    boolean DEFAULT_ENABLED = true;

    /** @return the persisted preference, or {@link #DEFAULT_ENABLED} when unset */
    boolean read();

    /** Persists the preference; a failure must surface as an exception, never as a silent success. */
    boolean save(boolean value);

    /**
     * @return a service reporting the default on {@link #read()} and refusing {@link #save(boolean)}
     *         with {@link IllegalStateException}, for runtimes that cannot persist this preference
     */
    static MeshTriangulationSettingsService unavailable() {
        return new MeshTriangulationSettingsService() {
            @Override
            public boolean read() {
                return DEFAULT_ENABLED;
            }

            @Override
            public boolean save(final boolean value) {
                throw new IllegalStateException("mesh triangulation settings are unavailable");
            }
        };
    }
}
