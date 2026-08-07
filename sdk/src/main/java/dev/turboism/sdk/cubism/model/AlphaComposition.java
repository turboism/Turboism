package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/**
 * Version-neutral Cubism drawable alpha-composition (transparency blending) mode.
 *
 * <p>Mirrors the Editor {@code AlphaComposition} enum introduced in Cubism 5.3;
 * on Cubism 5.2 hosts alpha-composition writes fail closed.
 */
@PreviewApi
public enum AlphaComposition {
    OVER,
    ATOP,
    OUT,
    CONJOINT,
    DISJOINT
}
