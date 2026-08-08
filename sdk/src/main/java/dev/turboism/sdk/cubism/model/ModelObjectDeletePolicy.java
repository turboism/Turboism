package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Explicit reference handling for structural deletion. */
@PreviewApi
public enum ModelObjectDeletePolicy {
    /** Reject deletion while children, masks, glue relations, or other references still exist. */
    REJECT_REFERENCED,
    /** Delegate dependent-object handling to the native Cubism deletion command. */
    CASCADE
}
