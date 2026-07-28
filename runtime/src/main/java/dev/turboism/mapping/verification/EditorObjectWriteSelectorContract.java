package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive write contracts for Editor ArtMesh, Warp, and Rotation authoring state. */
public final class EditorObjectWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String ART_MESH_CAPABILITY_ID = "cubism.editor-model.art-mesh.write";
    public static final String WARP_CAPABILITY_ID = "cubism.editor-model.warp-deformer.write";
    public static final String ROTATION_CAPABILITY_ID = "cubism.editor-model.rotation-deformer.write";

    private static final Set<String> COMMON_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.class",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable-source.set-visible",
        "cubism.editor-model.parameter-controllable-source.set-locked",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    public static final Set<String> ART_MESH_REQUIRED_ALIASES = union(
        COMMON_ALIASES,
        Set.of(
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.drawable-form.set-opacity",
            "cubism.editor-model.art-mesh-form.set-positions",
            "cubism.editor-model.art-mesh-source.set-positions",
            "cubism.editor-model.art-mesh-source.set-uvs",
            "cubism.editor-model.art-mesh-source.set-indices"
        )
    );

    public static final Set<String> WARP_REQUIRED_ALIASES = union(
        COMMON_ALIASES,
        Set.of(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            "cubism.editor-model.deformer-form.set-opacity",
            "cubism.editor-model.warp-source.set-row",
            "cubism.editor-model.warp-source.set-col",
            "cubism.editor-model.warp-source.set-quad-transform",
            "cubism.editor-model.warp-form.set-positions"
        )
    );

    public static final Set<String> ROTATION_REQUIRED_ALIASES = union(
        COMMON_ALIASES,
        Set.of(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            "cubism.editor-model.deformer-form.set-opacity",
            "cubism.editor-model.rotation-source.set-base-angle",
            "cubism.editor-model.rotation-form.set-angle",
            "cubism.editor-model.rotation-form.set-origin-x",
            "cubism.editor-model.rotation-form.set-origin-y",
            "cubism.editor-model.rotation-form.set-scale",
            "cubism.editor-model.rotation-form.set-reflect-x",
            "cubism.editor-model.rotation-form.set-reflect-y"
        )
    );

    private static Set<String> union(final Set<String> first, final Set<String> second) {
        final java.util.HashSet<String> values = new java.util.HashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private EditorObjectWriteSelectorContract() {
    }
}
