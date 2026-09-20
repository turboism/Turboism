package dev.turboism.sdk.cubism.edit;

/**
 * Who ended an edit session through cancellation.
 *
 * <p>Mirrors the official external-integration cancellation taxonomy: cancellation can come from
 * the plugin itself (an explicit cancel or a closing {@code EditEnd} with cancel), from the human
 * operating the Cubism Editor UI, or from the host forcibly terminating the session when the
 * plugin or document disappears.
 */
public enum CancelSource {
    /** The human cancelled the modal editing dialog or closed the project mid-session. */
    USER,
    /** The plugin called {@link EditSession#cancel()} or closed the session requesting rollback. */
    PLUGIN,
    /**
     * The host forcibly cancelled the session, for example because the plugin was disabled, the
     * document was closed, or the model was replaced while the session was open.
     */
    HOST
}
