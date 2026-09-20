package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/**
 * Receives session-level notifications from the host while an edit session is open.
 *
 * <p>Registering a listener on {@link EditSessionOptions#undoCancelListener()} opts the session
 * into undo-cancel notifications, equivalent to the official {@code NotifyUndoCancel} request
 * with {@code Enabled = true}. Sessions without a listener behave as if the notification is
 * disabled.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
@FunctionalInterface
public interface EditSessionListener {

    /**
     * Called when the host reports that an undo operation cancelled edits belonging to this
     * session. The {@code source} identifies who triggered the undo.
     *
     * @param session the session that received the notification
     * @param source who triggered the undo cancellation
     */
    void onUndoCancelled(EditSession session, CancelSource source);
}
