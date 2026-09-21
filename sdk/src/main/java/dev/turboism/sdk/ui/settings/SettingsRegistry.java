package dev.turboism.sdk.ui.settings;

import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;

/** Plugin-scoped registry for contributions to the shared Turboism settings window. */
public interface SettingsRegistry {

    /**
     * Registers a settings contribution. Closing the returned {@link Registration} withdraws
     * the contributed tab content.
     */
    Registration contribute(SettingsContribution contribution);

    /** Returns a fail-closed registry that rejects every contribution. */
    static SettingsRegistry unavailable() {
        return contribution -> {
            Objects.requireNonNull(contribution, "contribution");
            throw new UnsupportedOperationException("settings contribution is unavailable");
        };
    }
}
