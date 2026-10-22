package dev.turboism.config;

import dev.turboism.internal.core.AtlasCacheReuseSettingsService;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the atlas cache-reuse preference in the canonical runtime configuration.
 *
 * <p>Mirrors the atlas tile-bbox service: the same repository, the same write path, and the
 * same rule that a persistence failure reaches the caller instead of being swallowed.</p>
 */
public final class AtlasCacheReuseSettingsFileService implements AtlasCacheReuseSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public AtlasCacheReuseSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> {}));
    }

    AtlasCacheReuseSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean read() {
        try {
            return Boolean.parseBoolean(PerformanceSettingsConfig.saved(
                    config.read(),
                    PerformanceSettingsConfig.Field.ATLAS_CACHE_REUSE,
                    dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    @Override
    public boolean save(final boolean value) {
        PerformanceSettingsConfig.save(
                config, PerformanceSettingsConfig.Field.ATLAS_CACHE_REUSE, Boolean.toString(value));
        return value;
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
