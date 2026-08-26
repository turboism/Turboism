package dev.turboism.sdk.ui.settings;

import java.util.Objects;

/** Terminal localized result of a user-initiated settings action. */
public record SettingsActionResult(boolean succeeded, String title, String message) {

    public SettingsActionResult {
        title = requireText(title, "title");
        message = requireText(message, "message");
    }

    /**
     * Creates a successful terminal result.
     *
     * @param title localized result title
     * @param message localized result detail
     * @return the successful result
     */
    public static SettingsActionResult succeeded(final String title, final String message) {
        return new SettingsActionResult(true, title, message);
    }

    /**
     * Creates a failed or cancelled terminal result.
     *
     * @param title localized result title
     * @param message localized result detail
     * @return the unsuccessful result
     */
    public static SettingsActionResult failed(final String title, final String message) {
        return new SettingsActionResult(false, title, message);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 4096) {
            throw new IllegalArgumentException(name + " must contain 1-4096 characters");
        }
        return value;
    }
}
