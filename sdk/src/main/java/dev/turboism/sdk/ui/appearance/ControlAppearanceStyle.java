package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Optional native-control appearance properties; at least one must be present. */
@PreviewApi
public record ControlAppearanceStyle(
    Optional<UiColor> foreground,
    Optional<UiColor> background,
    Optional<UiFont> font
) {
    public ControlAppearanceStyle {
        foreground = Objects.requireNonNull(foreground, "foreground");
        background = Objects.requireNonNull(background, "background");
        font = Objects.requireNonNull(font, "font");
        if (foreground.isEmpty() && background.isEmpty() && font.isEmpty()) {
            throw new IllegalArgumentException("control appearance style must not be empty");
        }
    }
}
