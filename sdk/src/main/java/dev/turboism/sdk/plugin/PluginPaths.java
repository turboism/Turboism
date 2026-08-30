package dev.turboism.sdk.plugin;

import java.nio.file.Path;

/**
 * Persistent and runtime paths available to a plugin.
 */
public interface PluginPaths {

    /** Persistent plugin settings. */
    default Path configDir() {
        return dataDir();
    }

    Path dataDir();

    /**
     * @deprecated Plugins must write diagnostic records through {@link PluginContext#logger()}.
     *     Direct filesystem access to runtime logs is not available.
     * @return no path; this compatibility method always fails closed
     * @throws UnsupportedOperationException on every call
     */
    @Deprecated(forRemoval = true)
    default Path logsDir() {
        throw new UnsupportedOperationException(
            "direct plugin log paths are unavailable; use PluginContext.logger()"
        );
    }

    Path stateDir();

    Path cacheDir();
}
