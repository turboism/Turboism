package dev.turboism.home;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * One-shot relocation of a pre-layout {@code <home>/plugin-data} tree into the current
 * {@link TurboismHomeLayout} directories.
 *
 * <p>Migration is conservative: a file already present at the destination is left alone and the
 * legacy copy is kept, so re-running can never overwrite live data. Only empty directories are
 * deleted afterwards, so anything not understood survives in place.
 */
public final class LegacyHomeMigration {

    private LegacyHomeMigration() {
    }

    /**
     * Moves each legacy per-plugin directory into the plugin's config, data, cache, and state
     * directories under the current layout. Legacy plugin logs have no private runtime location
     * and remain in the legacy tree for user inspection.
     *
     * <p>A no-op when no {@code plugin-data} directory exists. A legacy directory whose name is
     * not a valid plugin id is skipped rather than migrated, so a crafted directory name cannot
     * write outside the layout. Legacy {@code data/typed-config} is routed to the config
     * directory and the rest of {@code data} to the data directory. Files are moved with an
     * atomic move, one at a time; there is no transaction across files, so a failure part-way
     * leaves the remaining files in the legacy tree for the next run.
     *
     * @param home the Turboism home root to migrate in place
     * @throws IOException if walking, creating or moving fails, including
     *     {@link java.nio.file.AtomicMoveNotSupportedException} when source and destination are
     *     on different file stores
     */
    public static void migrate(final Path home) throws IOException {
        final TurboismHomeLayout layout = TurboismHomeLayout.create(home);
        final Path legacyRoot = layout.home().resolve("plugin-data");
        if (!Files.isDirectory(legacyRoot)) {
            return;
        }
        try (var plugins = Files.list(legacyRoot)) {
            for (Path plugin : plugins.filter(Files::isDirectory).toList()) {
                final PluginHomePaths target;
                try {
                    target = layout.plugin(plugin.getFileName().toString());
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                moveTree(plugin.resolve("data/typed-config"), target.configDir());
                moveDataTree(plugin.resolve("data"), target.dataDir());
                moveTree(plugin.resolve("cache"), target.cacheDir());
                moveTree(plugin.resolve("state"), target.stateDir());
                deleteEmptyTree(plugin);
            }
        }
        deleteEmptyTree(legacyRoot);
    }

    private static void moveDataTree(final Path source, final Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        final Path typedConfig = source.resolve("typed-config");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(path -> !path.startsWith(typedConfig)).toList()) {
                moveFile(source, target, path);
            }
        }
        deleteEmptyTree(source);
    }

    private static void moveTree(final Path source, final Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                moveFile(source, target, path);
            }
        }
        deleteEmptyTree(source);
    }

    private static void moveFile(final Path source, final Path target, final Path path) throws IOException {
        final Path destination = target.resolve(source.relativize(path));
        if (Files.exists(destination)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        Files.move(path, destination, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteEmptyTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isDirectory(path)) {
                    try (var entries = Files.list(path)) {
                        if (entries.findAny().isEmpty()) {
                            Files.delete(path);
                        }
                    }
                }
            }
        }
    }
}
