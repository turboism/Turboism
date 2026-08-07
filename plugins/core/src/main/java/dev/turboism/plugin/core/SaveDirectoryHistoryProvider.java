package dev.turboism.plugin.core;

import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

/**
 * Core-plugin persistence backend for {@link FileChooserHistoryService}.
 *
 * <p>Stores both recent-directory slots in
 * {@code <home>/config/dev.turboism.plugin.core/save-directory-history.properties}
 * ({@code context.paths().configDir()}). The two slots are
 * independent: saving one slot preserves the other. Loading tolerates a
 * missing file (empty) and malformed lines (ignored by {@link Properties}).
 */
public final class SaveDirectoryHistoryProvider implements FileChooserHistoryService.Provider {

    /** Properties file name under the plugin config dir. */
    public static final String FILE_NAME = "save-directory-history.properties";

    private static final String PROJECT_DIRECTORY = "projectRecentDirectory";
    private static final String EXPORT_DIRECTORY = "exportRecentDirectory";

    private final Path file;

    public SaveDirectoryHistoryProvider(final Path configDir) {
        this.file = Objects.requireNonNull(configDir, "configDir").resolve(FILE_NAME);
    }

    @Override
    public Optional<Path> loadProjectDirectory() {
        return load(PROJECT_DIRECTORY);
    }

    @Override
    public Optional<Path> loadExportDirectory() {
        return load(EXPORT_DIRECTORY);
    }

    @Override
    public void saveProjectDirectory(final Path dir) {
        save(PROJECT_DIRECTORY, dir);
    }

    @Override
    public void saveExportDirectory(final Path dir) {
        save(EXPORT_DIRECTORY, dir);
    }

    private Optional<Path> load(final String key) {
        final String value = readAll().getProperty(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private void save(final String key, final Path dir) {
        Objects.requireNonNull(dir, "dir");
        try {
            Files.createDirectories(file.getParent());
            final Properties properties = readAll();
            properties.setProperty(key, dir.toString());
            try (OutputStream out = Files.newOutputStream(file)) {
                properties.store(out, null);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                "failed to persist file-chooser history to " + file, failure);
        }
    }

    /**
     * Reads the store tolerantly: a missing/unreadable file is empty, and a
     * malformed line (e.g. a broken backslash-u escape) is skipped instead
     * of failing the whole load.
     */
    private Properties readAll() {
        final Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                try (StringReader reader = new StringReader(line)) {
                    final Properties single = new Properties();
                    single.load(reader);
                    single.stringPropertyNames()
                        .forEach(name -> properties.setProperty(name, single.getProperty(name)));
                } catch (IllegalArgumentException malformed) {
                    // Ignore the malformed line.
                }
            }
        } catch (IOException unavailable) {
            return new Properties();
        }
        return properties;
    }
}
