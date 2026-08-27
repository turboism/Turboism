package dev.turboism.mapping.verification.selector;

import java.util.Set;

/** Exact public selectors required for immutable native Undo-history snapshots. */
public final class EditorHistoryReadSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = "cubism.editor-history.read";
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.document.undo-manager",
        "cubism.editor-history.manager.class",
        "cubism.editor-history.manager.entries",
        "cubism.editor-history.manager.position",
        "cubism.editor-history.manager.can-undo",
        "cubism.editor-history.manager.can-redo",
        "cubism.editor-history.entry.class",
        "cubism.editor-history.entry.presentation-name",
        "cubism.editor-history.entry.significant"
    );

    private EditorHistoryReadSelectorContract() {
    }
}
