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
        try {
            return Boolean.parseBoolean(PerformanceSettingsConfig.saved(
                    config.read(),
                    PerformanceSettingsConfig.Field.ATLAS_TILE_BBOX,
                    dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton()));
        } catch (RuntimeException invalid) {
            return false;
        }
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
