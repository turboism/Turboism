package dev.turboism.sdk.ui.settings;


import java.util.Objects;
import java.util.Optional;

/** Validation result for a proposed settings value before it enters the form state. */
public record SettingsChangeDecision(
    boolean accepted,
    String title,
    String message,
    Optional<SettingsLink> link
) {
    public SettingsChangeDecision {
        title = Objects.requireNonNull(title, "title");
        message = Objects.requireNonNull(message, "message");
        link = Objects.requireNonNull(link, "link");
        if (accepted && (!title.isEmpty() || !message.isEmpty() || link.isPresent())) {
            throw new IllegalArgumentException("an accepted decision must not carry rejection UI");
        }
        if (!accepted && (title.isBlank() || message.isBlank())) {
            throw new IllegalArgumentException("a rejected decision requires a title and message");
        }
    }

    /** @return an accepted decision carrying no rejection UI */
    public static SettingsChangeDecision allow() {
        return new SettingsChangeDecision(true, "", "", Optional.empty());
    }

    /**
     * Creates a rejected decision without a help link.
     *
     * @param title localized rejection title
     * @param message localized rejection message
     * @return a rejected decision
     */
    public static SettingsChangeDecision rejected(final String title, final String message) {
        return new SettingsChangeDecision(false, title, message, Optional.empty());
    }

    /**
     * Creates a rejected decision with a bounded help link.
     *
     * @param title localized rejection title
     * @param message localized rejection message
     * @param link help link displayed with the rejection
     * @return a rejected decision
     */
    public static SettingsChangeDecision rejected(
        final String title,
        final String message,
        final SettingsLink link
    ) {
        return new SettingsChangeDecision(false, title, message, Optional.of(link));
    }
}
