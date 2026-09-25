package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact additive selector contract for reading the Editor default-keyform lock state. */
public final class EditorDefaultKeyformLockReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";
    public static final String CAPABILITY_ID = "cubism.editor-model.default-keyform-lock.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.model-source.default-keyform-locked"
    );

    private EditorDefaultKeyformLockReadSelectorContract() {
    }
}
