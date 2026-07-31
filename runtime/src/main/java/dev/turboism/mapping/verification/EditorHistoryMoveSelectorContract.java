package dev.turboism.mapping.verification;

import java.util.Set;

/** Exact public selector required to move the active document's native Undo cursor. */
public final class EditorHistoryMoveSelectorContract {

    public static final String CAPABILITY_ID = "cubism.editor-history.move";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.manager.move-to"
    );

    private EditorHistoryMoveSelectorContract() {
    }
}
