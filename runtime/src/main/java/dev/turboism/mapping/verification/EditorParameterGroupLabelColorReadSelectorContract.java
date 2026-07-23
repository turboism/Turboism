package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for reading effective parameter-group label colors. */
public final class EditorParameterGroupLabelColorReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.parameter-group-label-color.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.parameter-group.label-color",
        "cubism.editor-model.label-color.class",
        "cubism.editor-model.label-color.color",
        "cubism.editor-model.color.class",
        "cubism.editor-model.color.red",
        "cubism.editor-model.color.green",
        "cubism.editor-model.color.blue",
        "cubism.editor-model.color.alpha"
    );

    private EditorParameterGroupLabelColorReadSelectorContract() {
    }
}
