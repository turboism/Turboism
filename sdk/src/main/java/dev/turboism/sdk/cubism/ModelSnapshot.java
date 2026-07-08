package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;

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
