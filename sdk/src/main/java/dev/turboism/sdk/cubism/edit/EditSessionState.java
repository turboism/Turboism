package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/** Lifecycle state of an {@link EditSession}. */
@Incubating
@CubismEditor(from = "5.2.03", to = "5.3.99")
public enum EditSessionState {
    /** The session is admitted and may run edit operations. */
    OPEN,
    /** The session ended through cancellation; the host restored the model and recorded no history. */
    CANCELLED,
    /** The session ended through {@link EditSession#close()}; model edits are committed. */
    CLOSED
}
