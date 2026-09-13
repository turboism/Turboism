package dev.turboism.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.shell.MeshTriangulationSettingsService;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists the mesh triangulation preference in the canonical runtime configuration.
 *
 * <p>Mirrors the Cubism JVM preference service: the same repository, the same write path, and the
 * same rule that a persistence failure reaches the caller instead of being swallowed.</p>
 */
public final class MeshTriangulationSettingsFileService
        implements MeshTriangulationSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public MeshTriangulationSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> { }));
    }

    MeshTriangulationSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean read() {
        try {
            final JsonNode value = config.read().get(MeshTriangulationPreference.KEY);
            if (value == null || !value.isBoolean()) return DEFAULT_ENABLED;
            return value.booleanValue();
        } catch (RuntimeException unreadable) {
            return DEFAULT_ENABLED;
        }
    }

    @Override
    public boolean save(final boolean value) {
        config.update(root -> {
            root.put(MeshTriangulationPreference.KEY, value);
            return root;
        });
        return value;
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
