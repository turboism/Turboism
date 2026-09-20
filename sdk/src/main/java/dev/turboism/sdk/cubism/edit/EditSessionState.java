package dev.turboism.sdk.cubism.edit;

/** Lifecycle state of an {@link EditSession}. */
public enum EditSessionState {
    /** The session is admitted and may run edit operations. */
    OPEN,
    /** The session ended through cancellation; the host restored the model and recorded no history. */
    CANCELLED,
    /** The session ended through {@link EditSession#close()}; model edits are committed. */
    CLOSED
}
