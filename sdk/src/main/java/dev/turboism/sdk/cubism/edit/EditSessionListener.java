package dev.turboism.sdk.cubism.edit;

/**
 * Receives session-level notifications from the host while an edit session is open.
 *
 * <p>Registering a listener on {@link EditSessionOptions#undoCancelListener()} opts the session
 * into undo-cancel notifications, equivalent to the official {@code NotifyUndoCancel} request
 * with {@code Enabled = true}. Sessions without a listener behave as if the notification is
 * disabled.
 */
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
