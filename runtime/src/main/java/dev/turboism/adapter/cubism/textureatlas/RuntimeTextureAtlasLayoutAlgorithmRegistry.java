package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Connection-owned registry of texture-atlas layout algorithms. */
public final class RuntimeTextureAtlasLayoutAlgorithmRegistry implements TextureAtlasLayoutAlgorithmRegistry {

    private final Map<String, TextureAtlasLayoutAlgorithm> algorithms = new LinkedHashMap<>();

    @Override
    public synchronized void register(final TextureAtlasLayoutAlgorithm algorithm) {
        Objects.requireNonNull(algorithm, "algorithm");
        algorithms.put(algorithm.id(), algorithm);
    }

    @Override
    public synchronized Optional<TextureAtlasLayoutAlgorithm> find(final String id) {
        return Optional.ofNullable(algorithms.get(id));
    }

    @Override
    public synchronized List<TextureAtlasLayoutAlgorithm> algorithms() {
        return List.copyOf(new ArrayList<>(algorithms.values()));
    }
}
