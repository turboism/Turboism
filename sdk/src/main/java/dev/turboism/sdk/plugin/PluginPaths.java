package dev.turboism.sdk.plugin;

import java.nio.file.Path;

/**
 * Worktree-local paths available to a plugin.
 */
public interface PluginPaths {

    Path dataDir();

    Path logsDir();

    Path stateDir();

    Path cacheDir();
}
