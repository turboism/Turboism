package dev.turboism.home;

import dev.turboism.sdk.plugin.PluginPaths;

import java.nio.file.Path;

/**
 * The storage paths handed to a plugin as its {@link PluginPaths}.
 *
 * <p>The config, data, and cache paths identify plugin-owned locations but are not materialised
 * merely by being handed out. Their confined storage services create and validate them at the
 * first operation that needs them. State preserves its runtime-owned lifecycle. Plugin diagnostics
 * are written through the shared runtime logger; no raw runtime-log path is exposed.
 *
 * @param configDir user-editable configuration, including typed config; expected to survive
 *     upgrades
 * @param dataDir plugin-owned persistent data
 * @param cacheDir regenerable data that may be deleted between runs without loss
 * @param stateDir internal runtime state that is not user-facing configuration
 */
public record PluginHomePaths(
    Path configDir,
    Path dataDir,
    Path cacheDir,
    Path stateDir
) implements PluginPaths {
}
