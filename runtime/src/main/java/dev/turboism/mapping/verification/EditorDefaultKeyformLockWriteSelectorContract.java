package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for editing the Editor default-keyform lock state. */
public final class EditorDefaultKeyformLockWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.default-keyform-lock.write";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.app-controller.main-frame",
        "cubism.editor-model.main-frame.parameter-palette",
        "cubism.editor-model.parameter-palette.view",
        "cubism.editor-model.parameter-palette-view.operation",
        "cubism.editor-model.parameter-operation.refresh",
        "cubism.editor-model.complete-pack.update-parameter",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.model-source.default-keyform-locked",
        "cubism.editor-model.model-source.set-default-keyform-locked"
    );

    private EditorDefaultKeyformLockWriteSelectorContract() {
    }
}
