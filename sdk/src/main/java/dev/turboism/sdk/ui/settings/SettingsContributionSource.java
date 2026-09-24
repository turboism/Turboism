package dev.turboism.sdk.ui.settings;


import java.util.List;

/** Read-only process aggregate handed only to the runtime-owned settings renderer. */
@FunctionalInterface
public interface SettingsContributionSource {

    /** Returns the process-wide settings tabs in render order. */
    List<SettingsSnapshot.Tab> snapshot();

    /** Returns a source that contributes no tabs. */
    static SettingsContributionSource empty() {
        return List::of;
    }
}
