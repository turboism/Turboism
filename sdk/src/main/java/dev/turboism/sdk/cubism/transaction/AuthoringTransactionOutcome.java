package dev.turboism.sdk.cubism.transaction;

/** Terminal outcome of one synchronous Editor authoring transaction callback. */
public enum AuthoringTransactionOutcome {
    /** At least one authoring write changed state and committed as one history entry. */
    COMMITTED,
    /** The callback completed but every operation was read-only or a no-op. */
    NO_CHANGE,
    /** A callback failure occurred and the original authoring state was proven restored. */
    ROLLED_BACK,
    /** The requested document, model, or history precondition was stale. */
    REJECTED_STALE,
    /** The callback could not join the required plugin, document, model, thread, or nesting scope. */
    REJECTED_SCOPE,
    /** No verified authoring-transaction implementation is available. */
    UNAVAILABLE,
    /** Restoration after a failed mutation could not be proven. */
    RECOVERY_FAILED
}
