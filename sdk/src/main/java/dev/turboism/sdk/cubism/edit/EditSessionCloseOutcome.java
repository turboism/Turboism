package dev.turboism.sdk.cubism.edit;

/** How an {@link EditSession} terminated. */
public enum EditSessionCloseOutcome {
    /**
     * The session closed normally: the model keeps the edits and the editor may record one
     * history entry covering the whole session (the official {@code EditEnd} contract).
     */
    COMMITTED,
    /**
     * The session was cancelled: the editor restored the model to its pre-session state and no
     * history entry was recorded.
     */
    CANCELLED,
    /**
     * The host attempted to end the session but the close itself failed. {@link
     * EditSessionCloseResult#diagnosticId()} carries the reason.
     */
    FAILED
}
