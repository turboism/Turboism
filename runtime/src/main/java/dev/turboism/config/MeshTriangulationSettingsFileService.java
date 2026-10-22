package dev.turboism.config;

import dev.turboism.internal.core.MeshTriangulationSettingsService;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the mesh triangulation preference in the canonical runtime configuration.
 *
 * <p>Mirrors the Cubism JVM preference service: the same repository, the same write path, and the
 * same rule that a persistence failure reaches the caller instead of being swallowed.</p>
 */
public final class MeshTriangulationSettingsFileService implements MeshTriangulationSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public MeshTriangulationSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> {}));
    }

    MeshTriangulationSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean read() {
        try {
            return Boolean.parseBoolean(PerformanceSettingsConfig.saved(
                    config.read(),
                    PerformanceSettingsConfig.Field.MESH_TRIANGULATION_HASH,
                    dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton()));
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    @Override
    public boolean save(final boolean value) {
        PerformanceSettingsConfig.save(
                config, PerformanceSettingsConfig.Field.MESH_TRIANGULATION_HASH, Boolean.toString(value));
        return value;
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
