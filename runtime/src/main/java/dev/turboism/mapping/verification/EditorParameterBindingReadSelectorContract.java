package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for Editor parameter-binding snapshots. */
public final class EditorParameterBindingReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = EditorObjectReadSelectorContract.ADAPTER_SLICE_ID;
    public static final String CAPABILITY_ID = "cubism.editor-model.parameter-bindings.read";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.parameter-controllable.keyform-grid",
        "cubism.editor-model.keyform-grid.class",
        "cubism.editor-model.keyform-grid.bindings",
        "cubism.editor-model.keyform-binding.class",
        "cubism.editor-model.keyform-binding.parameter-id",
        "cubism.editor-model.keyform-binding.keys",
        "cubism.editor-model.id.value"
    );

    private EditorParameterBindingReadSelectorContract() {
    }
}
