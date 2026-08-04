package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for writing Editor-native control label backgrounds
 * (parameter folders, Part labels/folders, Deformer labels/control rows).
 */
public final class EditorNativeControlAppearanceWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.native-control-appearance.write";
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
        "cubism.editor-model.complete-pack.update-part-palette",
        "cubism.editor-model.complete-pack.update-deformer-palette",
        "cubism.editor-model.complete-pack.repaint-canvas",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.simple-undo.create",
        "cubism.editor-model.parameter-group.label-color",
        "cubism.editor-model.parameter-controllable-source.label-color",
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.label-color.color",
        "cubism.editor-model.label-color.set-color",
        "cubism.editor-model.label-color.set-label-type",
        "cubism.editor-model.label-color-type.class",
        "cubism.editor-model.label-color-type.undefined",
        "cubism.editor-model.label-color-type.custom",
        "cubism.editor-model.label-color-type.red",
        "cubism.editor-model.label-color-type.orange",
        "cubism.editor-model.label-color-type.yellow",
        "cubism.editor-model.label-color-type.green",
        "cubism.editor-model.label-color-type.blue",
        "cubism.editor-model.label-color-type.purple",
        "cubism.editor-model.label-color-type.gray",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.create",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
    );

    private EditorNativeControlAppearanceWriteSelectorContract() {
    }
}
