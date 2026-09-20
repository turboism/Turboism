package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/** Alpha-composition modes understood by the editor, matching the official enumeration. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public enum EditAlphaBlend {
    OVER,
    ATOP,
    OUT,
    CONJOINT,
    DISJOINT
}
