package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/**
 * One resolved control-appearance state: the native authoring value plus the currently
 * resolved plugin transient overlay, each independently empty when absent.
 */
@PreviewApi
public record ControlAppearanceSnapshot(
    Optional<NativeControlAppearance> nativeAppearance,
    Optional<ControlAppearanceStyle> transientOverlay
) {

    public ControlAppearanceSnapshot {
        nativeAppearance = Objects.requireNonNull(nativeAppearance, "nativeAppearance");
        transientOverlay = Objects.requireNonNull(transientOverlay, "transientOverlay");
    }
}
