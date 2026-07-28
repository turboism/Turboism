package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for Cubism 5.2 Part evaluation-opacity reads. */
public final class EditorPartOpacity52SelectorContract {

    public static final String CUBISM_VERSION = "5.2.0";
    public static final String ADAPTER_SLICE_ID = EditorPartOpacitySelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.part-opacity.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.parts",
        "cubism.editor-model.model.parts",
        "cubism.editor-model.part.class",
        "cubism.editor-model.part.parts-opacity",
        "cubism.editor-model.part.source",
        "cubism.editor-model.part-source.class",
        "cubism.editor-model.part-source.id",
        "cubism.editor-model.part-source.parent",
        "cubism.editor-model.part-id.class",
        "cubism.editor-model.part-id.value"
    );

    private EditorPartOpacity52SelectorContract() {
    }
}
