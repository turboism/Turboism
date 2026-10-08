package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/** Alpha-composition modes understood by the editor, matching the official enumeration. */
@Incubating
@CubismEditor(from = "5.2.03", to = "5.3.99")
public enum EditAlphaBlend {
    OVER,
    ATOP,
    OUT,
    CONJOINT,
    DISJOINT
}
