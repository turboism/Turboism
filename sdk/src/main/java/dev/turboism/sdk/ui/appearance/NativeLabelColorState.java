package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/** Native label-color value plus the host-rendered color when the host exposes one. */
@PreviewApi
public record NativeLabelColorState(
    NativeLabelColor labelColor,
    Optional<UiColor> actualColor
) {
    public NativeLabelColorState {
        labelColor = Objects.requireNonNull(labelColor, "labelColor");
        actualColor = Objects.requireNonNull(actualColor, "actualColor");
    }
}
