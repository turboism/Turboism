package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Immutable RGBA color projected from a Cubism backend. */
@PreviewApi
public record Color(float red, float green, float blue, float alpha) {

    public Color {
        requireFinite(red, "red");
        requireFinite(green, "green");
        requireFinite(blue, "blue");
        requireFinite(alpha, "alpha");
    }

    private static void requireFinite(final float value, final String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
