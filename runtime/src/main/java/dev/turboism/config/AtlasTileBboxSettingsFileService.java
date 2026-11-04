package dev.turboism.config;

import dev.turboism.internal.core.AtlasTileBboxSettingsService;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the atlas tile-bbox preference in the canonical runtime configuration.
 *
 * <p>Mirrors the Cubism JVM preference service: the same repository, the same write path, and the
 * same rule that a persistence failure reaches the caller instead of being swallowed.</p>
 */
public final class AtlasTileBboxSettingsFileService implements AtlasTileBboxSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public AtlasTileBboxSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> {}));
    }

    AtlasTileBboxSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean read() {
        return PerformanceSettingsConfig.readSavedBoolean(config, PerformanceSettingsConfig.Field.ATLAS_TILE_BBOX);
    }

    @Override
    public boolean save(final boolean value) {
        PerformanceSettingsConfig.save(
                config, PerformanceSettingsConfig.Field.ATLAS_TILE_BBOX, Boolean.toString(value));
        return value;
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
