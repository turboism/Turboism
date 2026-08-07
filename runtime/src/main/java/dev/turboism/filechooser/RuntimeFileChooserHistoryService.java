package dev.turboism.filechooser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.config.RuntimeConfigRepository;
import dev.turboism.sdk.cubism.filechooser.FileChooserHistoryService;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime {@link FileChooserHistoryService} persisted in the global
 * {@code <turboism.home>/config.json} ({@code fileChooserHistory} section and
 * {@code hooks.startup.separateExportSaveDirectory}).
 */
public final class RuntimeFileChooserHistoryService implements FileChooserHistoryService {

    private static final String SECTION = "fileChooserHistory";
    private static final String PROJECT_DIRECTORY = "projectRecentDirectory";
    private static final String EXPORT_DIRECTORY = "exportRecentDirectory";
    private static final String SEPARATE_EXPORT = "separateExportSaveDirectory";

    private final RuntimeConfigRepository config;

    public RuntimeFileChooserHistoryService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public Optional<Path> projectRecentDirectory() {
        return directory(PROJECT_DIRECTORY);
    }

    @Override
    public Optional<Path> exportRecentDirectory() {
        return directory(EXPORT_DIRECTORY);
    }

    @Override
    public void setProjectRecentDirectory(final Path dir) {
        setDirectory(PROJECT_DIRECTORY, dir);
    }

    @Override
    public void setExportRecentDirectory(final Path dir) {
        setDirectory(EXPORT_DIRECTORY, dir);
    }

    @Override
    public boolean exportSeparationEnabled() {
        return config.read().path("hooks").path("startup").path(SEPARATE_EXPORT).asBoolean(false);
    }

    private Optional<Path> directory(final String field) {
        final JsonNode root = config.read();
        if (!root.has(SECTION) || !root.get(SECTION).isObject()) {
            return Optional.empty();
        }
        final String value = root.get(SECTION).path(field).asText("");
        return value.isBlank() ? Optional.empty() : Optional.of(Path.of(value));
    }

    private void setDirectory(final String field, final Path dir) {
        Objects.requireNonNull(dir, "dir");
        config.update(root -> {
            final ObjectNode section = root.withObject(SECTION);
            section.put(field, dir.toString());
            return root;
        });
    }
}
