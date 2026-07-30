package dev.turboism.home;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

public final class LegacyHomeMigration {

    private LegacyHomeMigration() {
    }

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
                moveTree(plugin.resolve("logs"), target.logsDir());
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
