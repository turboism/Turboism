package dev.turboism.sdk.cubism;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Aggregate, immutable view of everything the runtime observed about the Cubism host at one
 * instant. Assembled on the Cubism host thread and then safe to hand to plugin code on any thread:
 * every list component is defensively copied with {@link List#copyOf}.
 *
 * @param project the open project session, empty when the Editor has no project open
 * @param document the active editor document, empty when nothing is open or focused
 * @param model the model owned by the active document, empty unless that document is a model
 *     document
 * @param selection what the user currently has selected; always present, possibly empty selection
 * @param modelObjects unmodifiable copy of every parameter, art mesh and deformer as the common
 *     {@link ModelObjectSnapshot} supertype
 * @param parameters unmodifiable copy of the model's parameters
 * @param artMeshes unmodifiable copy of the model's art meshes
 * @param deformers unmodifiable copy of the model's deformers
 */
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
