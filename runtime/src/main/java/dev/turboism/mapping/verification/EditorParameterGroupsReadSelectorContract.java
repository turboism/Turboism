package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for reading the Editor parameter-group hierarchy. */
public final class EditorParameterGroupsReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.parameter-groups.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.root-parameter-group",
        "cubism.editor-model.parameter-group.class",
        "cubism.editor-model.parameter-group.id",
        "cubism.editor-model.parameter-group.name",
        "cubism.editor-model.parameter-group.parent",
        "cubism.editor-model.parameter-group.children",
        "cubism.editor-model.parameter-source.class",
        "cubism.editor-model.parameter-source.id",
        "cubism.editor-model.id.value"
    );

    private EditorParameterGroupsReadSelectorContract() {
    }
}
