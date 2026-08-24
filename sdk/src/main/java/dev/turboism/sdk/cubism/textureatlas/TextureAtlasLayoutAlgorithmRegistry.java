package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.plugin.Registration;

import java.util.List;
import java.util.Optional;

/**
 * Framework registry of texture-atlas layout algorithms. Plugins register their
 * algorithms at enable time; the runtime dialog contribution lists them and routes
 * the automatic-layout invocation to the selected algorithm.
 */
public interface TextureAtlasLayoutAlgorithmRegistry {

    /**
     * Registers an algorithm. Replacing an existing id is allowed; closing the
     * returned registration removes only that exact registration generation.
     * Registering a {@code null} algorithm is rejected.
     */
    Registration register(TextureAtlasLayoutAlgorithm algorithm);

    /** Finds a registered algorithm by id, if present. */
    Optional<TextureAtlasLayoutAlgorithm> find(String id);

    /** All registered algorithms, in registration order. */
    List<TextureAtlasLayoutAlgorithm> algorithms();
}
