package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Color;

import java.util.Objects;
import java.util.Optional;

/**
 * Resolved native label background of one Editor control.
 *
 * <p>{@code background} is the exact native authoring value (UNDEFINED/preset/custom) and
 * {@code effectiveBackground} is the effective color the Editor currently renders, when one is
 * exposed. The effective background is explicitly unavailable (empty) for the native UNDEFINED
 * label type, because the host exposes no effective color there; callers must not fabricate a
 * transparent, white, or latent-custom color in its place.</p>
 */
@PreviewApi
public record NativeControlAppearance(
    NativeControlBackground background,
    Optional<Color> effectiveBackground
) {

    public NativeControlAppearance {
        background = Objects.requireNonNull(background, "background");
        effectiveBackground = Objects.requireNonNull(effectiveBackground, "effectiveBackground");
    }
}
