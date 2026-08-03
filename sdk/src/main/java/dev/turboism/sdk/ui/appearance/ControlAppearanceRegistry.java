package dev.turboism.sdk.ui.appearance;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.plugin.Registration;

/**
 * Registers plugin-generation-scoped native-control appearance overlays and reads or writes the
 * Editor-native control label background.
 */
@PreviewApi
public interface ControlAppearanceRegistry {

    Registration register(ControlAppearanceContribution contribution);

    /** Resolves the native authoring value and the currently resolved transient overlay. */
    ControlAppearanceSnapshot snapshot(ControlAppearanceTarget target);

    /** Writes the Editor-native label background of the target's control. */
    void setNativeBackground(ControlAppearanceTarget target, NativeControlBackground background);

    static ControlAppearanceRegistry unavailable() {
        return new ControlAppearanceRegistry() {
            @Override public Registration register(final ControlAppearanceContribution contribution) {
                throw unavailable();
            }

            @Override public ControlAppearanceSnapshot snapshot(final ControlAppearanceTarget target) {
                throw unavailable();
            }

            @Override public void setNativeBackground(
                final ControlAppearanceTarget target,
                final NativeControlBackground background
            ) {
                throw unavailable();
            }

            private static UnsupportedOperationException unavailable() {
                return new UnsupportedOperationException(
                    "control appearance registry is not available"
                );
            }
        };
    }
}
