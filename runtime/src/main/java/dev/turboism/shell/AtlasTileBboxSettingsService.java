package dev.turboism.shell;

/**
 * Runtime-owned preference for the atlas tile-bbox scratch optimization, exposed only to the
 * built-in Core plugin.
 *
 * <p>One private host method rebuilds every model image through page-sized scratch buffers, so
 * each image pays whole-page raster work. Enabling this preference lets the runtime replace that
 * method body with a delegate whose scratch, merge and composite bounds are shrunk to the
 * transformed-tile bounding box — proven pixel-identical on the reviewed host on both the
 * editor-open and export paths; disabling it leaves the host class exactly as the vendor
 * shipped it.</p>
 *
 * <p>The value is read during JVM startup, so a change applies from the next Cubism launch.</p>
 */
public interface AtlasTileBboxSettingsService {

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
    static AtlasTileBboxSettingsService unavailable() {
        return new AtlasTileBboxSettingsService() {
            @Override
            public boolean read() {
                return DEFAULT_ENABLED;
            }

            @Override
            public boolean save(final boolean value) {
                throw new IllegalStateException("atlas tile-bbox settings are unavailable");
            }
        };
    }
}
