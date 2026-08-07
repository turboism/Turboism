package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/**
 * Version-neutral Cubism drawable color-composition (color blending) mode.
 *
 * <p>Every value mirrors the corresponding Editor {@code ColorComposition} enum
 * constant name. Cubism 5.2 hosts expose only {@link #NORMAL}, {@link #ADD} and
 * {@link #MULTIPLY}; all other values fail closed on those hosts.
 */
@PreviewApi
public enum ColorComposition {
    NORMAL,
    ADD,
    MULTIPLY,
    ADD_R2_TSL,
    ADD_R2,
    DARKEN,
    MULTIPLY_R2,
    COLORBURN_TSL,
    LINEARBURN_TSL,
    LIGHTEN,
    SCREEN,
    COLORDODGE_TSL,
    OVERLAY,
    SOFTLIGHT,
    HARDLIGHT,
    LINEARLIGHT_TSL,
    HSL_HUE,
    HSL_COLOR
}
