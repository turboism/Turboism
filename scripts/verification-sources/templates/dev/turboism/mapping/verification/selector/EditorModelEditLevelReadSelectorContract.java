package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact additive selector contract for reading Cubism Editor's model edit level. */
public final class EditorModelEditLevelReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";
    public static final String CAPABILITY_ID = "cubism.editor-model.edit-level.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.edit-level"
    );

    private EditorModelEditLevelReadSelectorContract() {
    }
}
