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
 * <p>The selectors are admitted for the reviewed 5.2.03, 5.3.02 and 5.3.03 records: each
 * record's selectors were verified directly against that version's reviewed artifact.</p>
 */
public final class EditorHistoryIngressSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.editor-model.readwrite";
    public static final String CAPABILITY_ID = EditorHistoryReadSelectorContract.CAPABILITY_ID;

    /**
     * The inherited {@code beginEdit} entry of {@code com.live2d.cubism.doc.ACEditMode}, which the
     * scene and game-data editors reach.
     *
     * <p>The modeling editor does not reach it: {@code CModelingEditMode_Main} overrides
     * {@code beginEdit} and, while form animation is active, returns through
     * {@code CModelEditAnimationHandler} without calling {@code super}. It is admitted because the
     * scene editor ({@code CSceneEditMode}) and the game-data editor
     * ({@code CGameDataEditMode_Main} via {@code CGameDataEditMode_Base}) both resolve their
     * {@code beginEdit} to this one. {@code CSceneEditMode} declares no override of its own, so
     * there is no third target.</p>
     */
    public static final String BASE_EDIT_ENTRY_ALIAS = "cubism.editor-history.edit-mode.begin";

    /**
     * The modeling editor's own {@code beginEdit} entry, which the four HR-05 ingress families
     * actually reach.
     *
     * <p>It belongs to the mature Editor surface and is already admitted under its own alias, so it
     * is deliberately <em>not</em> part of {@link #REQUIRED_ALIASES}: adding it there would change
     * what exact Cubism 5.3.03 excludes, and 5.3.03 is expected to keep excluding this whole
     * family.</p>
     */
    public static final String MODELING_EDIT_ENTRY_ALIAS = "cubism.editor-model.edit-mode.begin";

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.editor-history.manager.undo-state-listener",
        "cubism.editor-history.manager.undo-state-listener-remove",
        "cubism.editor-history.undo-state-listener.class",
        "cubism.editor-history.edit-mode.begin"
    );

    private EditorHistoryIngressSelectorContract() {
    }
}
