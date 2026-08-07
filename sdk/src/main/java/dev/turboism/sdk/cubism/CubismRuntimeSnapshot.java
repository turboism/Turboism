package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record CubismRuntimeSnapshot(
    Optional<ProjectSnapshot> project,
    Optional<DocumentSnapshot> document,
    Optional<ModelSnapshot> model,
    SelectionSnapshot selection,
    List<ModelObjectSnapshot> modelObjects,
    List<ParameterSnapshot> parameters,
    List<ArtMeshSnapshot> artMeshes,
    List<DeformerSnapshot> deformers
) {
    public CubismRuntimeSnapshot {
        project = Objects.requireNonNull(project, "project");
        document = Objects.requireNonNull(document, "document");
        model = Objects.requireNonNull(model, "model");
        selection = Objects.requireNonNull(selection, "selection");
        modelObjects = List.copyOf(Objects.requireNonNull(modelObjects, "modelObjects"));
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        artMeshes = List.copyOf(Objects.requireNonNull(artMeshes, "artMeshes"));
        deformers = List.copyOf(Objects.requireNonNull(deformers, "deformers"));
    }

    /** Animation file owning the active scene document, when applicable. */
    public Optional<AnimationSnapshot> animation() {
        return document.flatMap(DocumentSnapshot::animation);
    }
}
