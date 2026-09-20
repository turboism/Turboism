package dev.turboism.mapping.verification.selector;

import java.util.HashSet;
import java.util.Set;

/**
 * Exact additive selector contract for external-application edit sessions (the ported
 * {@code GetIsEditApproval}, {@code EditBegin}, {@code EditEnd}, {@code EditSendLog},
 * {@code EditSendProgress}, and {@code NotifyUndoCancel} semantics of the Cubism 5.4 alpha2
 * integration surface).
 *
 * <p>Every member below is declared with the precise owner internal name, member name, JVM
 * descriptor, and access flags observed on the exact Cubism 5.2.03, 5.3.02, and 5.3.03 host
 * artifacts. The capability ids in this contract are declared ahead of their verification
 * records: until a record lists them, {@code authorizesFeature} rejects every row and the edit
 * surface fails closed.</p>
 */
public final class EditorEditSessionSelectorContract {

    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-editor-model.json:adapterSliceId}";

    public static final String GET_IS_EDIT_APPROVAL_CAPABILITY_ID =
        "cubism.editor-model.edit.session.get-is-edit-approval";
    public static final String EDIT_BEGIN_CAPABILITY_ID =
        "cubism.editor-model.edit.session.edit-begin";
    public static final String EDIT_END_CAPABILITY_ID =
        "cubism.editor-model.edit.session.edit-end";
    public static final String EDIT_SEND_LOG_CAPABILITY_ID =
        "cubism.editor-model.edit.session.edit-send-log";
    public static final String EDIT_SEND_PROGRESS_CAPABILITY_ID =
        "cubism.editor-model.edit.session.edit-send-progress";
    public static final String NOTIFY_UNDO_CANCEL_CAPABILITY_ID =
        "cubism.editor-model.edit.session.notify-undo-cancel";

    /**
     * Navigation members every session operation needs to reach the modeling document, its edit
     * mode, and the main frame that hosts the editing dialog.
     */
    public static final Set<String> SESSION_NAVIGATION_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.main-frame",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.modeling-document.edit-mode"
    );

    /**
     * The write-path envelope an admitted session can hand to edit operations: edit-mode
     * bracketing, undo capture, dirty marking, and instance/palette refresh. Shared with the
     * per-family contracts so they do not restate it.
     */
    public static final Set<String> EDIT_SESSION_WRITE_ENVELOPE_ALIASES = Set.of(
        "cubism.editor-model.app-controller.instance",
        "cubism.editor-model.app-controller.current-document",
        "cubism.editor-model.app-controller.complete-pack",
        "cubism.editor-model.modeling-document.model-source",
        "cubism.editor-model.modeling-document.edit-mode",
        "cubism.editor-model.modeling-document.mark-dirty",
        "cubism.editor-model.edit-mode.begin",
        "cubism.editor-model.edit-mode.end",
        "cubism.editor-model.undo.add",
        "cubism.editor-model.undo.add-listener",
        "cubism.editor-model.undo-listener.class",
        "cubism.editor-model.model-source.update-instances",
        "cubism.editor-model.complete-pack.repaint-canvas"
    );

    /**
     * {@code GetIsEditApproval} is answered by Turboism's own authorization layer on these hosts;
     * the supported editors expose no edit-approval member.
     */
    public static final Set<String> GET_IS_EDIT_APPROVAL_REQUIRED_ALIASES = Set.of();

    /** {@code EditBegin}: enter the document's modeling edit mode. */
    public static final Set<String> EDIT_BEGIN_REQUIRED_ALIASES = unionAll(
        SESSION_NAVIGATION_ALIASES,
        Set.of("cubism.editor-model.edit-mode.begin")
    );

    /** {@code EditEnd}: leave edit mode and reconcile the native undo history position. */
    public static final Set<String> EDIT_END_REQUIRED_ALIASES = unionAll(
        SESSION_NAVIGATION_ALIASES,
        Set.of(
            "cubism.editor-model.edit-mode.end",
            "cubism.editor-history.document.undo-manager",
            "cubism.editor-history.manager.class",
            "cubism.editor-history.manager.entries",
            "cubism.editor-history.manager.position",
            "cubism.editor-history.manager.move-to"
        )
    );

    /** {@code EditSendLog}: forwarded to Turboism's own dialog/log channel; no host member. */
    public static final Set<String> EDIT_SEND_LOG_REQUIRED_ALIASES = Set.of();

    /** {@code EditSendProgress}: forwarded to Turboism's own dialog; no host member. */
    public static final Set<String> EDIT_SEND_PROGRESS_REQUIRED_ALIASES = Set.of();

    /**
     * {@code NotifyUndoCancel}: subscribe to the undo listener surface and observe the history
     * cursor so undo-driven cancellations reach the session.
     */
    public static final Set<String> NOTIFY_UNDO_CANCEL_REQUIRED_ALIASES = unionAll(
        SESSION_NAVIGATION_ALIASES,
        Set.of(
            "cubism.editor-model.undo.add",
            "cubism.editor-model.undo.add-listener",
            "cubism.editor-model.undo-listener.class",
            "cubism.editor-history.document.undo-manager",
            "cubism.editor-history.manager.class",
            "cubism.editor-history.manager.position",
            "cubism.editor-history.manager.move-to"
        )
    );

    private static Set<String> unionAll(final Set<String>... sets) {
        final HashSet<String> values = new HashSet<>();
        for (final Set<String> set : sets) {
            values.addAll(set);
        }
        return Set.copyOf(values);
    }

    private EditorEditSessionSelectorContract() {
    }
}
