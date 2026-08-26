package dev.turboism.sdk.ui.settings;

import java.util.Objects;

/** Immutable bounded progress snapshot for a settings action. */
public record SettingsActionProgress(long completed, long total, String message) {

    public SettingsActionProgress {
        message = Objects.requireNonNull(message, "message");
        if (completed < 0L || total < 0L || completed > total) {
            throw new IllegalArgumentException("settings action progress is invalid");
        }
    }

    /**
     * Creates a progress snapshot whose total amount is not known.
     *
     * @param message localized progress message
     * @return an indeterminate progress snapshot
     */
    public static SettingsActionProgress indeterminate(final String message) {
        return new SettingsActionProgress(0L, 0L, message);
    }
}
