package dev.turboism.sdk.cubism.mirror;

/** Typed reason a {@link WarpMirrorRequest} was rejected or could not complete. */
public enum WarpMirrorBlockerCode {
    /** The mirror capability is not admitted on this host/version. */
    UNAVAILABLE,
    /** The target id no longer resolves to a live Warp Deformer. */
    TARGET_MISSING,
    /** The target or a required ancestor is locked. */
    TARGET_LOCKED,
    /** Descendant preservation was requested and a descendant is locked. */
    DESCENDANT_LOCKED,
    /** Descendant preservation is unavailable while Glue objects are present. */
    GLUE_PRESENT,
    /**
     * The object's current keyform is not a stored keyform (interpolated or otherwise
     * non-persistent editing context); the operation refuses to write or auto-create keyforms.
     */
    NON_STORED_KEYFORM,
    /** The grid is degenerate (collapsed bounds, too few divisions, or non-finite data). */
    DEGENERATE_GEOMETRY,
    /** No source/target control-point pairs could be formed across the axis. */
    NO_PAIR,
    /**
     * Descendant preservation could not be solved or proven: the inverse transform exceeded
     * tolerance, a descendant cannot represent the preserved geometry, or the post-write
     * residual check failed.
     */
    UNSOLVABLE_COMPENSATION,
    /** A host write failed inside the transaction and was rolled back. */
    WRITE_FAILED,
    /** The document/model/selection context changed between capture and apply. */
    STALE_TARGET
}
