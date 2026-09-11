package dev.turboism.mapping.verification.selector;

import java.util.Set;

/**
 * Exact public selectors required to observe native Editor Undo-history state changes.
 *
 * <p>These selectors let the runtime register a state-change listener on the active document's
 * native undo manager, so an edit performed through the Cubism UI — which never enters the
 * Turboism facade — can still be observed. The listener is a trigger only: it carries no
 * verified fact and must not be treated as commit proof. The facts themselves come from the
 * already-admitted history read aliases in {@link EditorHistoryReadSelectorContract}.</p>
 *
 * <p>The listener contract deliberately reuses the {@code cubism.editor-history.read}
 * capability: registering an observer on the undo manager reads history state and performs no
 * model mutation, so no new capability is admitted.</p>
 *
 * <p>The selectors are admitted for the reviewed 5.2.03 and 5.3.02 records only. Exact Cubism
 * 5.3.03 keeps the native semantic-history ingress deliberately absent until that version has
 * its own reviewed evidence.</p>
 */
public final class EditorHistoryIngressSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = EditorHistoryReadSelectorContract.CAPABILITY_ID;
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.manager.undo-state-listener",
        "cubism.editor-history.manager.undo-state-listener-remove",
        "cubism.editor-history.undo-state-listener.class"
    );

    private EditorHistoryIngressSelectorContract() {
    }
}
