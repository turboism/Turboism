package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Color;

import java.util.Objects;
import java.util.Optional;

/**
 * Optional native-control appearance properties; at least one must be present. Authoring colors
 * are Turboism {@link Color} values and must be finite and in {@code [0,1]}.
 */
@PreviewApi
public record ControlAppearanceStyle(
    Optional<Color> foreground,
    Optional<Color> background,
    Optional<UiFont> font
) {

    public ControlAppearanceStyle {
        foreground = requireUnitColor(foreground, "foreground");
        background = requireUnitColor(background, "background");
        font = Objects.requireNonNull(font, "font");
        if (foreground.isEmpty() && background.isEmpty() && font.isEmpty()) {
            throw new IllegalArgumentException("control appearance style must not be empty");
        }
    }

    private static Optional<Color> requireUnitColor(final Optional<Color> value, final String name) {
        Objects.requireNonNull(value, name);
        value.ifPresent(color -> requireUnit(color, name));
        return value;
    }

    private static void requireUnit(final Color color, final String name) {
        if (!Float.isFinite(color.red()) || color.red() < 0.0F || color.red() > 1.0F
            || !Float.isFinite(color.green()) || color.green() < 0.0F || color.green() > 1.0F
            || !Float.isFinite(color.blue()) || color.blue() < 0.0F || color.blue() > 1.0F
            || !Float.isFinite(color.alpha()) || color.alpha() < 0.0F || color.alpha() > 1.0F) {
            throw new IllegalArgumentException(
                name + " must be finite and in [0,1], but was " + color
            );
        }
    }
}
