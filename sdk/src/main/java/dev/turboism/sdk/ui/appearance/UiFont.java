package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Bounded font override; absent fields retain the native control value. */
@PreviewApi
public record UiFont(
    Optional<String> family,
    Optional<Float> size,
    Weight weight,
    Posture posture
) {
    public UiFont {
        family = Objects.requireNonNull(family, "family").map(UiFont::family);
        size = Objects.requireNonNull(size, "size").map(UiFont::size);
        weight = Objects.requireNonNull(weight, "weight");
        posture = Objects.requireNonNull(posture, "posture");
    }

    private static String family(final String value) {
        final String trimmed = Objects.requireNonNull(value, "family value").trim();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 128) {
            throw new IllegalArgumentException("font family must contain 1 to 128 code points");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (Character.isISOControl(trimmed.charAt(index))) {
                throw new IllegalArgumentException("font family must not contain control characters");
            }
        }
        return trimmed;
    }

    private static float size(final float value) {
        if (!Float.isFinite(value) || value < 6.0F || value > 96.0F) {
            throw new IllegalArgumentException("font size must be between 6 and 96 points");
        }
        return value;
    }

    public enum Weight { INHERIT, REGULAR, BOLD }

    public enum Posture { INHERIT, NORMAL, ITALIC }
}
