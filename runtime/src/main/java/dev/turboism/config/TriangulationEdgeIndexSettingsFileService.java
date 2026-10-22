package dev.turboism.config;

import dev.turboism.internal.core.TriangulationEdgeIndexSettingsService;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the triangulation edge-index preference in the canonical runtime configuration.
 *
 * <p>Mirrors the mesh triangulation preference service: the same repository, the same write path,
 * and the same rule that a persistence failure reaches the caller instead of being swallowed.</p>
 */
public final class TriangulationEdgeIndexSettingsFileService
        implements TriangulationEdgeIndexSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public TriangulationEdgeIndexSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> {}));
    }

    TriangulationEdgeIndexSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean read() {
        try {
            return Boolean.parseBoolean(PerformanceSettingsConfig.saved(
                    config.read(),
                    PerformanceSettingsConfig.Field.TRIANGULATION_EDGE_INDEX,
                    dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    @Override
    public boolean save(final boolean value) {
        PerformanceSettingsConfig.save(
                config, PerformanceSettingsConfig.Field.TRIANGULATION_EDGE_INDEX, Boolean.toString(value));
        return value;
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
