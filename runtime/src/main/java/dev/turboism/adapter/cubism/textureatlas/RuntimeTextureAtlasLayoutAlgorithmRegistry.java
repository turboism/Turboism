package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.plugin.Registration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Connection-owned registry of texture-atlas layout algorithms. */
public final class RuntimeTextureAtlasLayoutAlgorithmRegistry implements TextureAtlasLayoutAlgorithmRegistry {

    private final Map<String, RegisteredAlgorithm> algorithms = new LinkedHashMap<>();

    @Override
    public synchronized Registration register(final TextureAtlasLayoutAlgorithm algorithm) {
        final TextureAtlasLayoutAlgorithm value = Objects.requireNonNull(
            algorithm,
            "algorithm"
        );
        final RegisteredAlgorithm registration = new RegisteredAlgorithm(value);
        algorithms.put(value.id(), registration);
        return () -> {
            synchronized (RuntimeTextureAtlasLayoutAlgorithmRegistry.this) {
                algorithms.remove(value.id(), registration);
            }
        };
    }

    @Override
    public synchronized Optional<TextureAtlasLayoutAlgorithm> find(final String id) {
        return Optional.ofNullable(algorithms.get(id))
            .map(RegisteredAlgorithm::algorithm);
    }

    @Override
    public synchronized List<TextureAtlasLayoutAlgorithm> algorithms() {
        return algorithms.values().stream()
            .map(RegisteredAlgorithm::algorithm)
            .toList();
    }

    private record RegisteredAlgorithm(TextureAtlasLayoutAlgorithm algorithm) {
        private RegisteredAlgorithm {
            algorithm = Objects.requireNonNull(algorithm, "algorithm");
        }
    }
}
