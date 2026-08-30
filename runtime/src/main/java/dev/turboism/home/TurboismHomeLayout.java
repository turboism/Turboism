package dev.turboism.home;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The on-disk directory layout of a Turboism home.
 *
 * <p>All plugin-visible storage is confined under one home root, and per-plugin paths are keyed
 * by a validated plugin id so one plugin cannot reach another's storage through a crafted id.
 * Plugin config, data, and cache directories are materialised only by their respective filesystem
 * operations.</p>
 */
public final class TurboismHomeLayout {

    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private final Path home;

    private TurboismHomeLayout(final Path home) {
        this.home = home;
    }

    /**
     * Creates the home layout, materialising the shared top-level directories.
     *
     * @param requestedHome the home root, resolved to an absolute normalised path
     * @return the layout rooted at that path
     * @throws IOException when a directory cannot be created
     * @throws NullPointerException when {@code requestedHome} is null
     */
    public static TurboismHomeLayout create(final Path requestedHome) throws IOException {
        final Path home = Objects.requireNonNull(requestedHome, "requestedHome")
            .toAbsolutePath().normalize();
        Files.createDirectories(home);
        for (String directory : List.of("plugins", "config", "data", "cache", "state", "logs")) {
            Files.createDirectories(home.resolve(directory));
        }
        return new TurboismHomeLayout(home);
    }

    /** @return the absolute, normalised home root */
    public Path home() {
        return home;
    }

    /** @return the runtime-wide configuration document */
    public Path globalConfig() {
        return home.resolve("config.json");
    }

    /** @return the directory plugin JARs are discovered from */
    public Path pluginsDir() {
        return home.resolve("plugins");
    }

    /** @return the runtime's own configuration directory */
    public Path runtimeConfigDir() {
        return home.resolve("config/runtime");
    }

    /** @return the runtime's own persistent data directory */
    public Path runtimeDataDir() {
        return home.resolve("data/runtime");
    }

    /** @return the runtime's own discardable cache directory */
    public Path runtimeCacheDir() {
        return home.resolve("cache/runtime");
    }

    /** @return the runtime's own session-state directory */
    public Path runtimeStateDir() {
        return home.resolve("state/runtime");
    }

    /** @return the runtime's own log directory */
    public Path runtimeLogsDir() {
        return home.resolve("logs/runtime");
    }

    /**
     * Returns the confined path set for one plugin.
     *
     * <p>Config, data, and cache paths are not materialised here. State retains its runtime-owned
     * lifecycle and is created before it is handed out. Plugin logs flow through the shared runtime
     * logger rather than a plugin-visible filesystem path.
     *
     * @param pluginId the plugin's declared id
     * @return the plugin's config, data, cache and state paths
     * @throws IllegalArgumentException when the id is null or does not match the accepted shape;
     *     this is the check that keeps one plugin's storage out of another's
     * @throws IOException when the state directory cannot be created
     */
    public PluginHomePaths plugin(final String pluginId) throws IOException {
        if (pluginId == null || !PLUGIN_ID.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("pluginId is invalid");
        }
        final PluginHomePaths paths = new PluginHomePaths(
            AnchoredDirectoryTree.anchor(home.resolve("config").resolve(pluginId)),
            AnchoredDirectoryTree.anchor(home.resolve("data").resolve(pluginId)),
            AnchoredDirectoryTree.anchor(home.resolve("cache").resolve(pluginId)),
            home.resolve("state").resolve(pluginId)
        );
        Files.createDirectories(paths.stateDir());
        return paths;
    }
}
