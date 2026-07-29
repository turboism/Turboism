package dev.turboism.home;

import dev.turboism.sdk.plugin.PluginPaths;

import java.nio.file.Path;

public record PluginHomePaths(
    Path configDir,
    Path dataDir,
    Path cacheDir,
    Path stateDir,
    Path logsDir
) implements PluginPaths {
}
