package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/**
 * Version-neutral Cubism Part alpha-composition mode, mirroring the Editor
 * Inspector {@code AlphaComposition} entry (evidence: 5302-src
 * {@code Parts_wrapperForInspector$alphaComposition$1}).
 */
@PreviewApi
public enum AlphaComposition {
    OVER,
    ATOP,
    OUT,
    CONJOINT,
    DISJOINT,
    /** Backend did not expose a recognizable composition value. */
    UNKNOWN
}
