package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Existing exact-5.3.02 primitives for non-default stored-form position and draw order edits. */
public final class EditorArtMeshKeyformSelectorContract {
    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = EditorInspectorDrawableWriteSelectorContract.CAPABILITY_ID;
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.art-mesh-form.set-positions",
        "cubism.editor-model.drawable-form.draw-order",
        "cubism.editor-model.drawable-form.set-draw-order",
        "cubism.editor-model.parameter-controllable-source.handler",
        "cubism.editor-model.parameter-controllable-handler.create-undo-for-keyform-edit",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorArtMeshKeyformSelectorContract() { }
}
