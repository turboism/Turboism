package dev.turboism.home;

import dev.turboism.sdk.plugin.PluginPaths;

import java.nio.file.Path;

/**
 * The five per-plugin directories handed to a plugin as its {@link PluginPaths}.
 *
 * <p>All five live under the plugin's own subtree of the Turboism home and are created by
 * {@link TurboismHomeLayout} before this record is handed out, so a plugin may write to them
 * without creating them first. The record itself performs no validation; it is only ever built by
 * the layout.
 *
 * @param configDir user-editable configuration, including typed config; expected to survive
 *     upgrades
 * @param dataDir plugin-owned persistent data
 * @param cacheDir regenerable data that may be deleted between runs without loss
 * @param stateDir internal runtime state that is not user-facing configuration
 * @param logsDir plugin-written log files
 */
public record PluginHomePaths(
    Path configDir,
    Path dataDir,
    Path cacheDir,
    Path stateDir,
    Path logsDir
) implements PluginPaths {
}
