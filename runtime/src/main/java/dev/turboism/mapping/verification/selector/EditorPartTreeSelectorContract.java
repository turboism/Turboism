package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact additive selector contract for Editor Part index and tree reads. */
public final class EditorPartTreeSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.part-tree.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.part-id.class",
        "cubism.editor-model.part-id.value"
    );

    private EditorPartTreeSelectorContract() {
    }
}
