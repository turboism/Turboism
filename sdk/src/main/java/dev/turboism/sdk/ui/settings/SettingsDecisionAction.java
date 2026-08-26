package dev.turboism.sdk.ui.settings;

import java.util.Objects;

/** Primary operation offered by a rejected settings change. */
public record SettingsDecisionAction(String label, SettingsAction action) {

    public SettingsDecisionAction {
        label = Objects.requireNonNull(label, "label");
        if (label.isBlank() || label.length() > 256) {
            throw new IllegalArgumentException("label must contain 1-256 characters");
        }
        action = Objects.requireNonNull(action, "action");
    }
}
