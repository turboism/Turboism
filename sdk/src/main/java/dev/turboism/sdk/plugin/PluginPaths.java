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

    Path logsDir();

    Path stateDir();

    Path cacheDir();
}
