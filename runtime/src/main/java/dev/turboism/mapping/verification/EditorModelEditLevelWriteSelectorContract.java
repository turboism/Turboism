package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact additive selector contract for switching Cubism Editor's model edit level. */
public final class EditorModelEditLevelWriteSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-model.edit-level.write";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.set-edit-level"
    );

    private EditorModelEditLevelWriteSelectorContract() {
    }
}
