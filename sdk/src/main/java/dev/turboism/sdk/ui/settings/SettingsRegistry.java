package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Plugin-scoped registry for contributions to the shared Turboism settings window. */
public interface SettingsRegistry {

    Registration contribute(SettingsContribution contribution);

    static SettingsRegistry unavailable() {
        return contribution -> {
            Objects.requireNonNull(contribution, "contribution");
            throw new UnsupportedOperationException("settings contribution is unavailable");
        };
    }
}
