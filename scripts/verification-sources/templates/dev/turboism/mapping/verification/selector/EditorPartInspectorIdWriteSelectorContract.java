package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact additive selector contract for the Editor Inspector Part {@code id} write on the host
 * whose {@link #CUBISM_VERSION} declares the bound version. Evidence: that artifact's
 * {@code Parts_wrapperForInspector} has only the id entry (no clippingMaskId /
 * alphaComposition / color entries), so those SDK writes fail closed there; the newer reviewed
 * artifact authorizes the full family through {@link EditorPartInspectorSelectorContract}.
 */
public final class EditorPartInspectorIdWriteSelectorContract {

    public static final String CUBISM_VERSION = "${record:cubism-5.2.03-editor-model.json:cubismVersion}";
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

    private EditorPartInspectorIdWriteSelectorContract() {
    }
}
