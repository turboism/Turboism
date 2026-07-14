package dev.turboism.preview;

import dev.turboism.sdk.plugin.PluginPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record PreviewPluginPaths(
    Path dataDir,
    Path logsDir,
    Path stateDir,
    Path cacheDir
) implements PluginPaths {

    static PreviewPluginPaths create(final Path home, final String pluginId) throws IOException {
        final Path pluginRoot = home.resolve("plugin-data").resolve(pluginId).toAbsolutePath().normalize();
        final PreviewPluginPaths paths = new PreviewPluginPaths(
            pluginRoot.resolve("data"),
            pluginRoot.resolve("logs"),
            pluginRoot.resolve("state"),
            pluginRoot.resolve("cache")
        );
        Files.createDirectories(paths.dataDir());
        Files.createDirectories(paths.logsDir());
        Files.createDirectories(paths.stateDir());
        Files.createDirectories(paths.cacheDir());
        return paths;
    }
}
