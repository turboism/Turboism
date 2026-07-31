package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selectors for atomic parameter-binding inversion and GUID transfer. */
public final class EditorParameterBindingBatchWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String INVERT_CAPABILITY_ID = "cubism.editor-model.parameter-bindings.invert";
    public static final String TRANSFER_CAPABILITY_ID = "cubism.editor-model.parameter-bindings.transfer";

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
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.parameter-guid",
        "cubism.editor-model.keyform-grid.find-binding",
        "cubism.editor-model.keyform-grid.reverse-parameter",
        "cubism.editor-model.keyform-grid.change-parameter",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorParameterBindingBatchWriteSelectorContract() {
    }
}
