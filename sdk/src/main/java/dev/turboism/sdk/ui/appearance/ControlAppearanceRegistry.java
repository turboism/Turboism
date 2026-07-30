package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.plugin.Registration;

/** Registers plugin-generation-scoped native-control appearance overlays. */
@PreviewApi
public interface ControlAppearanceRegistry {

    Registration register(ControlAppearanceContribution contribution);

    static ControlAppearanceRegistry unavailable() {
        return contribution -> {
            throw new UnsupportedOperationException("control appearance registry is not available");
        };
    }
}
