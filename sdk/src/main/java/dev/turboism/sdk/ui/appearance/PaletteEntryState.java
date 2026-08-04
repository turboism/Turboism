package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Five independent properties of one Cubism palette entry. */
@PreviewApi
public record PaletteEntryState(
    Optional<Float> fontSize,
    Optional<Boolean> bold,
    Optional<Boolean> italic,
    Optional<UiColor> textColor,
    Optional<UiColor> backgroundColor
) {

    public PaletteEntryState {
        fontSize = requireFontSize(fontSize);
        bold = Objects.requireNonNull(bold, "bold");
        italic = Objects.requireNonNull(italic, "italic");
        textColor = Objects.requireNonNull(textColor, "textColor");
        backgroundColor = Objects.requireNonNull(backgroundColor, "backgroundColor");
    }

    private static Optional<Float> requireFontSize(final Optional<Float> value) {
        Objects.requireNonNull(value, "fontSize");
        value.ifPresent(size -> {
            if (!Float.isFinite(size) || size < 6.0F || size > 96.0F) {
                throw new IllegalArgumentException("font size must be between 6 and 96 points");
            }
        });
        return value;
    }

    public static PaletteEntryState empty() {
        return new PaletteEntryState(
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()
        );
    }
}
