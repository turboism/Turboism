package dev.turboism.home;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public final class TurboismHomeLayout {

    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
    private final Path home;

    private TurboismHomeLayout(final Path home) {
        this.home = home;
    }

    public static TurboismHomeLayout create(final Path requestedHome) throws IOException {
        final Path home = Objects.requireNonNull(requestedHome, "requestedHome")
            .toAbsolutePath().normalize();
        Files.createDirectories(home);
        for (String directory : List.of("plugins", "config", "data", "cache", "state", "logs")) {
            Files.createDirectories(home.resolve(directory));
        }
        return new TurboismHomeLayout(home);
    }

    public Path home() {
        return home;
    }

    public Path globalConfig() {
        return home.resolve("config.json");
    }

    public Path pluginsDir() {
        return home.resolve("plugins");
    }

    public Path runtimeConfigDir() {
        return home.resolve("config/runtime");
    }

    public Path runtimeDataDir() {
        return home.resolve("data/runtime");
    }

    public Path runtimeCacheDir() {
        return home.resolve("cache/runtime");
    }

    public Path runtimeStateDir() {
        return home.resolve("state/runtime");
    }

    public Path runtimeLogsDir() {
        return home.resolve("logs/runtime");
    }

    public PluginHomePaths plugin(final String pluginId) throws IOException {
        if (pluginId == null || !PLUGIN_ID.matcher(pluginId).matches()) {
            throw new IllegalArgumentException("pluginId is invalid");
        }
        final PluginHomePaths paths = new PluginHomePaths(
            home.resolve("config").resolve(pluginId),
            home.resolve("data").resolve(pluginId),
            home.resolve("cache").resolve(pluginId),
            home.resolve("state").resolve(pluginId),
            home.resolve("logs").resolve(pluginId)
        );
        for (Path path : List.of(
            paths.configDir(), paths.dataDir(), paths.cacheDir(), paths.stateDir(), paths.logsDir()
        )) {
            Files.createDirectories(path);
        }
        return paths;
    }
}
