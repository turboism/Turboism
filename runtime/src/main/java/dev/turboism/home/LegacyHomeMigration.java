package dev.turboism.home;

import dev.turboism.core.io.DurableFile;
import dev.turboism.core.io.DurableWriteException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/**
 * One-shot relocation of a pre-layout {@code <home>/plugin-data} tree into the current
 * {@link TurboismHomeLayout} directories, plus the released plugin-id renames that changed where a
 * typed-config document lives.
 *
 * <p>Migration is conservative: a file already present at the destination is left alone and the
 * legacy copy is kept, so re-running can never overwrite live data. Only empty directories are
 * deleted afterwards, so anything not understood survives in place. The same rule applies to the
 * documented plugin-id rename: the previous release's document is copied to the renamed plugin's
 * config directory, the original stays byte-identical as a recoverable pre-upgrade image and an
 * existing document is never replaced.</p>
 */
public final class LegacyHomeMigration {

    /**
     * Plugin ids renamed by a published release, {@code previous -> current}. The typed-config root
     * is keyed by plugin id ({@code config/<pluginId>/}) while the document path inside it is
     * unchanged, so without this map the renamed plugin starts from defaults and the user's settings
     * stay behind unread under the retired id.
     *
     * <p>{@code dev.turboism.plugin.backup} was renamed to {@code dev.turboism.plugin.webdav} in
     * {@code v0.44.0} keeping {@code CONFIG_ID} {@code backup.webdav} and path
     * {@code backup/webdav.cfg}. {@code packaging/retired-plugins.txt} deletes the retired JAR on
     * upgrade, so nothing else ever reads the orphaned document.</p>
     */
    private static final Map<String, String> RENAMED_PLUGIN_IDS =
            Map.of("dev.turboism.plugin.backup", "dev.turboism.plugin.webdav");

    private LegacyHomeMigration() {}

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
     *     {@link DurableWriteException} with {@code ATOMIC_REPLACE_UNAVAILABLE} when source and
     *     destination are on different file stores
     */
    public static void migrate(final Path home) throws IOException {
        final TurboismHomeLayout layout = TurboismHomeLayout.create(home);
        migrateRenamedPluginConfigs(layout);
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

    /**
     * Copies every released document of a renamed plugin id to the current id's config directory.
     *
     * <p>Runs before the legacy {@code plugin-data} relocation and independently of it, because an
     * installation upgraded from {@code v0.43.x} has no {@code plugin-data} tree left. The original
     * document is never moved, never rewritten and never deleted here: it is the only pre-upgrade
     * image a user can fall back to, and losing it on a failed upgrade would be worse than the
     * orphan it already is. Symlinked paths are skipped instead of followed, so a crafted link in a
     * plugin directory cannot make the runtime read or copy a file outside the home.</p>
     */
    private static void migrateRenamedPluginConfigs(final TurboismHomeLayout layout) throws IOException {
        final Path configRoot = layout.home().resolve("config");
        for (final Map.Entry<String, String> rename : RENAMED_PLUGIN_IDS.entrySet()) {
            // Resolve the two config roots directly: asking the layout for the retired id would
            // materialise its state directory on every start for no reason.
            final Path source = configRoot.resolve(rename.getKey());
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source)) {
                continue;
            }
            final Path target = configRoot.resolve(rename.getValue());
            try (var paths = Files.walk(source)) {
                for (final Path path : paths.toList()) {
                    copyIfAbsent(path, target.resolve(source.relativize(path)));
                }
            }
        }
    }

    /**
     * Publishes one file at {@code destination} without consuming {@code source}.
     *
     * <p>An existing destination is authoritative and is never replaced; a symbolic link at either
     * end is refused. The copy is written to a same-directory temporary file and moved into place,
     * so a concurrent reader never observes a partially written document and a failure leaves no
     * half-copied file behind.</p>
     */
    private static void copyIfAbsent(final Path source, final Path destination) throws IOException {
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(destination) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.createDirectories(destination.getParent());
        DurableFile.createOnce(destination).replace(Files.readAllBytes(source));
    }

    private static void moveDataTree(final Path source, final Path target) throws IOException {
        if (!Files.isDirectory(source)) {
            return;
        }
        final Path typedConfig = source.resolve("typed-config");
        try (var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(typedConfig))
                    .toList()) {
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
        // Nothing exists at the destination, so this is a create-once publication: the durable
        // module forces the legacy file and fails closed when the move cannot be atomic.
        DurableFile.createOnce(destination).adoptKeepingSource(path);
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
