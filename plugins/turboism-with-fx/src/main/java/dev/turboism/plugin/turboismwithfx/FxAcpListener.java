package dev.turboism.plugin.turboismwithfx;

/** Thread-safe callbacks from one identified ACP client to the plugin controller. */
interface FxAcpListener {

    default void agentText(
        final FxAcpClient source,
        final String sessionId,
        final String text
    ) {
    }

    default void agentThought(
        final FxAcpClient source,
        final String sessionId,
        final String text
    ) {
    }

    default void toolCall(
        final FxAcpClient source,
        final String sessionId,
        final String toolCallId,
        final String title,
        final String kind,
        final String status
    ) {
    }

    default void toolCallUpdate(
        final FxAcpClient source,
        final String sessionId,
        final String toolCallId,
        final String status,
        final String content
    ) {
    }

    default void stderr(final FxAcpClient source, final String text) {
    }

    default void terminated(final FxAcpClient source, final String message) {
    }

    /**
     * Resolves an fx permission request. Implementations may block this dedicated reader dispatch
     * while presenting UI; closing or cancellation must return {@link PermissionDecision#CANCELLED}.
     */
    default PermissionDecision permission(
        final FxAcpClient source,
        final String sessionId,
        final PermissionRequest request
    ) {
        return PermissionDecision.CANCELLED;
    }

    enum PermissionDecision {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        REJECT_ONCE,
        CANCELLED
    }

    /**
     * Detached permission prompt supplied by fx.
     *
     * @param title human-readable operation label
     * @param kind ACP tool kind
     * @param toolCallId opaque tool-call correlation id
     * @param details bounded, bearer-redacted JSON arguments the user must review
     */
    record PermissionRequest(String title, String kind, String toolCallId, String details) {
    }
}
