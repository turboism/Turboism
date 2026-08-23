package dev.turboism.sdk.ui.appearance;


/** UI color value. It is deliberately separate from Cubism model colors. */
public record UiColor(float red, float green, float blue, float alpha) {

    public UiColor {
        requireUnit(red, "red");
        requireUnit(green, "green");
        requireUnit(blue, "blue");
        requireUnit(alpha, "alpha");
    }

    private static void requireUnit(final float value, final String name) {
        if (!Float.isFinite(value) || value < 0.0F || value > 1.0F) {
            throw new IllegalArgumentException(name + " must be finite and in [0,1], but was " + value);
        }
    }
}
