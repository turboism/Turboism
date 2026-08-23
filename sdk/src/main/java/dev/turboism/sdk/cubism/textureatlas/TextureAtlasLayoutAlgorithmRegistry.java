package dev.turboism.sdk.cubism.textureatlas;


import java.util.List;
import java.util.Optional;

/**
 * Framework registry of texture-atlas layout algorithms. Plugins register their
 * algorithms at enable time; the runtime dialog contribution lists them and routes
 * the automatic-layout invocation to the selected algorithm.
 */
public interface TextureAtlasLayoutAlgorithmRegistry {

    /**
     * Registers an algorithm. Replacing an existing id is allowed; registering a
     * {@code null} algorithm is rejected.
     */
    void register(TextureAtlasLayoutAlgorithm algorithm);

    /** Finds a registered algorithm by id, if present. */
    Optional<TextureAtlasLayoutAlgorithm> find(String id);

    /** All registered algorithms, in registration order. */
    List<TextureAtlasLayoutAlgorithm> algorithms();
}
