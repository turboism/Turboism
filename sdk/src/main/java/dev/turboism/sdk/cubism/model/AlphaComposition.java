package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/**
 * Version-neutral Cubism alpha-composition (transparency blending) mode for
 * both the drawable (ArtMesh) and Part Inspector surfaces.
 *
 * <p>Mirrors the Editor {@code AlphaComposition} enum introduced in Cubism 5.3
 * (evidence: 5302-src {@code Parts_wrapperForInspector$alphaComposition$1});
 * on Cubism 5.2 hosts alpha-composition writes fail closed.</p>
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
