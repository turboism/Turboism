package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Color;

import java.util.Objects;

/**
 * Resolved native label background of one Editor control.
 *
 * <p>{@code background} is the exact native authoring value (UNDEFINED/preset/custom) and
 * {@code effectiveBackground} is the effective color the Editor currently renders.</p>
 */
@PreviewApi
public record NativeControlAppearance(
    NativeControlBackground background,
    Color effectiveBackground
) {

    public NativeControlAppearance {
        background = Objects.requireNonNull(background, "background");
        effectiveBackground = Objects.requireNonNull(effectiveBackground, "effectiveBackground");
    }
}
