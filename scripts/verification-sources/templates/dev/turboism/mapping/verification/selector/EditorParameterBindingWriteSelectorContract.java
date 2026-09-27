package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact additive contracts for Editor parameter-binding authoring operations. */
public final class EditorParameterBindingWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String ART_MESH_CAPABILITY_ID = "cubism.editor-model.art-mesh.parameter-bindings.write";
    public static final String WARP_CAPABILITY_ID = "cubism.editor-model.warp-deformer.parameter-bindings.write";
    public static final String ROTATION_CAPABILITY_ID = "cubism.editor-model.rotation-deformer.parameter-bindings.write";

    /** Exact selectors shared by all ordinary parameter-binding writes. */
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.add-key",
        "cubism.editor-model.keyform-grid.remove-key",
        "cubism.editor-model.keyform-grid.remove-all-key",
        "cubism.editor-model.keyform-grid.rearrange-keys",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorParameterBindingWriteSelectorContract() {
    }
}
