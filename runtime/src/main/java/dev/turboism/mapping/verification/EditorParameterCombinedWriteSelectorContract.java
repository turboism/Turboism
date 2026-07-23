package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additional selector contract for Editor four-corner parameter pairing writes. */
public final class EditorParameterCombinedWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID =
        "cubism.editor-model.parameter-combined.write";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.main-frame",
        "cubism.editor-model.main-frame.parameter-palette",
        "cubism.editor-model.parameter-palette.view",
        "cubism.editor-model.parameter-palette-view.operation",
        "cubism.editor-model.parameter-operation.refresh",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.parameter-source.combined",
        "cubism.editor-model.parameter-source.set-combined",
        "cubism.editor-model.parameter-source.guid",
        "cubism.editor-model.parameter-source.parent-group",
        "cubism.editor-model.parameter-group.class",
        "cubism.editor-model.parameter-group.children",
        "cubism.editor-model.parameter-group.remove",
        "cubism.editor-model.parameter-group.add"
    );

    private EditorParameterCombinedWriteSelectorContract() {
    }
}
