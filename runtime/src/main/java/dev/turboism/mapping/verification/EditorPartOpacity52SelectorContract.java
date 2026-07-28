package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for Cubism 5.2 Editor Part authoring-opacity access. */
public final class EditorPartOpacity52SelectorContract {

    public static final String CUBISM_VERSION = "5.2.0";
    public static final String ADAPTER_SLICE_ID = EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = EditorPartOpacitySelectorContract.CAPABILITY_ID;
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
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.parts-opacity",
        "cubism.editor-model.part.set-parts-opacity",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.part-source.handler",
        "cubism.editor-model.part-handler.class",
        "cubism.editor-model.part-handler.create-undo-for-all-edit",
        "cubism.editor-model.part-id.class",
        "cubism.editor-model.part-id.value",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorPartOpacity52SelectorContract() {
    }
}
