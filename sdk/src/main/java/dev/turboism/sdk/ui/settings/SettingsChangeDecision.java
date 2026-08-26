package dev.turboism.sdk.ui.settings;


import java.util.Objects;
import java.util.Optional;

/** Validation result for a proposed settings value before it enters the form state. */
public record SettingsChangeDecision(
    boolean accepted,
    String title,
    String message,
    Optional<SettingsLink> link,
    Optional<SettingsDecisionAction> action
) {
    /**
     * Compatibility constructor for the original decision contract without an operation.
     *
     * @param accepted whether the proposed settings value is accepted
     * @param title localized rejection title, or empty when accepted
     * @param message localized rejection message, or empty when accepted
     * @param link optional help link displayed with a rejection
     */
    public SettingsChangeDecision(
        final boolean accepted,
        final String title,
        final String message,
        final Optional<SettingsLink> link
    ) {
        this(accepted, title, message, link, Optional.empty());
    }

    public SettingsChangeDecision {
        title = Objects.requireNonNull(title, "title");
        message = Objects.requireNonNull(message, "message");
        link = Objects.requireNonNull(link, "link");
        action = Objects.requireNonNull(action, "action");
        if (accepted && (!title.isEmpty() || !message.isEmpty()
            || link.isPresent() || action.isPresent())) {
            throw new IllegalArgumentException("an accepted decision must not carry rejection UI");
        }
        if (!accepted && (title.isBlank() || message.isBlank())) {
            throw new IllegalArgumentException("a rejected decision requires a title and message");
        }
    }

    /** @return an accepted decision carrying no rejection UI */
    public static SettingsChangeDecision allow() {
        return new SettingsChangeDecision(
            true, "", "", Optional.empty(), Optional.empty()
        );
    }

    /**
     * Creates a rejected decision without a help link.
     *
     * @param title localized rejection title
     * @param message localized rejection message
     * @return a rejected decision
     */
    public static SettingsChangeDecision rejected(final String title, final String message) {
        return new SettingsChangeDecision(
            false, title, message, Optional.empty(), Optional.empty()
        );
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
        return new SettingsChangeDecision(
            false, title, message, Optional.of(link), Optional.empty()
        );
    }

    /**
     * Creates a rejected decision with one explicit user-initiated operation.
     *
     * @param title localized rejection title
     * @param message localized rejection message
     * @param action operation offered by the settings renderer
     * @return a rejected decision
     */
    public static SettingsChangeDecision rejected(
        final String title,
        final String message,
        final SettingsDecisionAction action
    ) {
        return new SettingsChangeDecision(
            false, title, message, Optional.empty(), Optional.of(action)
        );
    }

    /** Creates a rejected decision carrying both an operation and a help link. */
    public static SettingsChangeDecision rejected(
        final String title,
        final String message,
        final SettingsDecisionAction action,
        final SettingsLink link
    ) {
        return new SettingsChangeDecision(
            false, title, message, Optional.of(link), Optional.of(action)
        );
    }
}
