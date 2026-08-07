package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for the Cubism 5.2 Editor Inspector Part
 * {@code id} write. Evidence: 52-src {@code Parts_wrapperForInspector} has only
 * the id entry (no clippingMaskId / alphaComposition / color entries), so those
 * SDK writes fail closed on 5.2.
 */
public final class EditorPartInspector52SelectorContract {

    public static final String CUBISM_VERSION = "5.2.0";
    public static final String ADAPTER_SLICE_ID = EditorPartInspectorSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.part-inspector.id-write";
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
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.model-source.verify",
        "cubism.editor-model.model-source.all-deformers",
        "cubism.editor-model.model-source.all-art-meshes",
        "cubism.editor-model.model-source.all-glues",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.set-id",
        "cubism.editor-model.parameter-controllable-source.id",
        "cubism.editor-model.id.value",
        "cubism.editor-model.part-id.create",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorPartInspector52SelectorContract() {
    }
}
