package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact feature-owned selector contract for one undo-enveloped parameter value write. */
public final class EditorParameterValueWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.write";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.app-controller.main-frame",
        "cubism.editor-model.modeling-document.class",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.model-source.class",
        "cubism.editor-model.model-source.current-instance",
        "cubism.editor-model.model-source.guid",
        "cubism.editor-model.guid.class",
        "cubism.editor-model.guid.value",
        "cubism.editor-model.model.class",
        "cubism.editor-model.model.parameter-set",
        "cubism.editor-model.parameter-set.class",
        "cubism.editor-model.parameter-set.parameters",
        "cubism.editor-model.parameter.class",
        "cubism.editor-model.parameter.id",
        "cubism.editor-model.parameter.value",
        "cubism.editor-model.parameter.source",
        "cubism.editor-model.parameter-source.class",
        "cubism.editor-model.parameter-source.minimum",
        "cubism.editor-model.parameter-source.maximum",
        "cubism.editor-model.id.class",
        "cubism.editor-model.id.value",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.main-frame.parameter-palette",
        "cubism.editor-model.parameter-palette.view",
        "cubism.editor-model.parameter-palette-view.operation",
        "cubism.editor-model.parameter-operation.set-value",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    private EditorParameterValueWriteSelectorContract() {
    }
}
