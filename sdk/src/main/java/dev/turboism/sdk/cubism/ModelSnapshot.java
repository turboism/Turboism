package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of one Cubism model and the objects it contains.
 *
 * <p>{@code objects} and the three typed lists are independent views assembled by the host adapter;
 * this record does not derive one from the others, nor does it guarantee that the typed lists
 * partition {@code objects}. All list components are defensively copied.</p>
 *
 * @param modelId stable Editor-assigned identifier of the model
 * @param name display name of the model
 * @param objects unmodifiable copy of the model's objects as the common supertype
 * @param parameters unmodifiable copy of the model's parameters
 * @param artMeshes unmodifiable copy of the model's art meshes
 * @param deformers unmodifiable copy of the model's deformers
 */
public record ModelSnapshot(
    String modelId,
    String name,
    List<ModelObjectSnapshot> objects,
    List<ParameterSnapshot> parameters,
    List<ArtMeshSnapshot> artMeshes,
    List<DeformerSnapshot> deformers
) {
    public ModelSnapshot {
        modelId = Objects.requireNonNull(modelId, "modelId");
        name = Objects.requireNonNull(name, "name");
        objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        artMeshes = List.copyOf(Objects.requireNonNull(artMeshes, "artMeshes"));
        deformers = List.copyOf(Objects.requireNonNull(deformers, "deformers"));
    }
}
