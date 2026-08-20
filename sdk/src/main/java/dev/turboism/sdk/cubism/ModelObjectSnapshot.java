package dev.turboism.sdk.cubism;

/**
 * Common supertype for the addressable objects of a model: parameters, art meshes and deformers.
 *
 * <p>The permits clause is closed on purpose, so callers can switch exhaustively over the three
 * kinds without a default branch. Implementations are records and therefore immutable.</p>
 */
public sealed interface ModelObjectSnapshot permits ParameterSnapshot, ArtMeshSnapshot, DeformerSnapshot {
    /** @return the object's stable Editor-assigned identifier, unique within the model */
    String id();

    /** @return the object's display name as shown in the Editor, which is not required to be unique */
    String name();
}
