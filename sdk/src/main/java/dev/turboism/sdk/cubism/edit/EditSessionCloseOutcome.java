package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/** How an {@link EditSession} terminated. */
@CubismEditor(from = "5.2.03", to = "5.3.99")
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
