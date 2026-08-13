package dev.turboism.mapping.verification;

import java.util.Set;

/**
 * Exact additive selector contract for reading Editor-native control label backgrounds
 * (parameter folders, Part labels/folders, Deformer labels/control rows).
 */
public final class EditorNativeControlAppearanceReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.native-control-appearance.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.parameter-group.label-color",
        "cubism.editor-model.parameter-controllable-source.label-color",
        "cubism.editor-model.deformer-source.class",
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.label-type",
        "cubism.editor-model.label-color.customized-color",
        "cubism.editor-model.label-color.color",
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
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
    );

    private EditorNativeControlAppearanceReadSelectorContract() {
    }
}
