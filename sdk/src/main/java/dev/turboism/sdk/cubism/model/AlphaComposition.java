package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;

/** Cubism alpha-composition mode for drawable and Part Inspector surfaces. */
@CubismEditor(from = "5.3.00")
public enum AlphaComposition {
    OVER,
    ATOP,
    OUT,
    CONJOINT,
    DISJOINT,
    /** Backend did not expose a recognizable composition value. */
    UNKNOWN
}
