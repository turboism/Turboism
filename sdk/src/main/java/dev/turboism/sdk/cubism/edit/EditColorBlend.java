package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
/**
 * Color-blend modes understood by the editor, matching the official enumeration.
 *
 * <p>{@link #ADD_5_2} and {@link #MULTIPLY_5_2} are the pre-5.3 blend semantics retained for
 * compatibility; the editor converts them on load depending on model version.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public enum EditColorBlend {
    NORMAL,
    ADD,
    ADD_GLOW,
    DARKEN,
    MULTIPLY,
    COLOR_BURN,
    LINEAR_BURN,
    LIGHTEN,
    SCREEN,
    COLOR_DODGE,
    OVERLAY,
    SOFT_LIGHT,
    HARD_LIGHT,
    LINEAR_LIGHT,
    HUE,
    COLOR,
    ADD_5_2,
    MULTIPLY_5_2
}
