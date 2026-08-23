package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.PreviewApi;

import java.util.List;

/** Read-only process aggregate handed only to the runtime-owned settings renderer. */
@PreviewApi
@FunctionalInterface
public interface SettingsContributionSource {

    List<SettingsSnapshot.Tab> snapshot();

    static SettingsContributionSource empty() {
        return List::of;
    }
}
