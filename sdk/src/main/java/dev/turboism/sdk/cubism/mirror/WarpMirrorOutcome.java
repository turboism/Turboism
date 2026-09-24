package dev.turboism.sdk.cubism.mirror;

/** Terminal outcome of one {@link WarpMirrorService#apply(WarpMirrorRequest)} call. */
public enum WarpMirrorOutcome {
    /** The mirrored grid (and any compensation writes) were committed as one Undo entry. */
    APPLIED,
    /** The grid was already symmetric for the requested direction; nothing was written. */
    NO_CHANGE,
    /** A precondition failed before any write; {@code blockers} carry the typed reasons. */
    BLOCKED,
    /**
     * The transaction failed mid-write and rollback could not be verified, or a post-write
     * preservation check failed without a clean recovery. No further writes were attempted.
     */
    RECOVERY_FAILED
}
