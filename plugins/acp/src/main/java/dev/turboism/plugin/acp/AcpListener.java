package dev.turboism.plugin.acp;

import java.util.List;

/** Thread-safe callbacks from one identified ACP client to the plugin controller. */
interface AcpListener {

    default void agentText(final AcpClient source, final String sessionId, final String text) {}

    default void agentThought(final AcpClient source, final String sessionId, final String text) {}

    default void toolCall(
            final AcpClient source,
            final String sessionId,
            final String toolCallId,
            final String title,
            final String kind,
            final String status) {}

    default void toolCallUpdate(
            final AcpClient source,
            final String sessionId,
            final String toolCallId,
            final String status,
            final String content) {}

    /** Agent-pushed replacement of the session configuration catalog. */
    default void configOptions(
            final AcpClient source, final String sessionId, final List<AcpConfigOption> options) {}

    /** Agent-pushed list of slash commands available for the session. */
    default void availableCommands(final AcpClient source, final String sessionId, final List<String> commands) {}

    default void stderr(final AcpClient source, final String text) {}

    default void terminated(final AcpClient source, final String message) {}

    /**
     * Resolves an agent permission request. Implementations may block this dedicated reader
     * dispatch while presenting UI; closing or cancellation must return {@link
     * PermissionDecision#CANCELLED}.
     */
    default PermissionDecision permission(
            final AcpClient source, final String sessionId, final PermissionRequest request) {
        return PermissionDecision.CANCELLED;
    }

    enum PermissionDecision {
        ALLOW_ONCE,
        ALLOW_ALWAYS,
        REJECT_ONCE,
        CANCELLED
    }

    /**
     * Detached permission prompt supplied by the agent.
     *
     * @param title human-readable operation label
     * @param kind ACP tool kind
     * @param toolCallId opaque tool-call correlation id
     * @param details bounded, bearer-redacted JSON arguments the user must review
     * @param options agent-advertised outcome ids mapped to the client's fixed decisions
     */
    record PermissionRequest(
            String title, String kind, String toolCallId, String details, AcpClient.PermissionOptionSet options) {}
}
