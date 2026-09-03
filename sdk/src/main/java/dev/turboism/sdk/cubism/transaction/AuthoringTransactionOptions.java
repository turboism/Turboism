package dev.turboism.sdk.cubism.transaction;

import java.util.Objects;

/**
 * Immutable options for one synchronous authoring transaction.
 *
 * @param label user-visible label associated with the single native Undo entry when a change commits
 */
public record AuthoringTransactionOptions(String label) {

    /** Maximum number of Java characters accepted in a transaction label. */
    public static final int MAX_LABEL_LENGTH = 128;

    /** Validates and normalizes the transaction label. */
    public AuthoringTransactionOptions {
        label = Objects.requireNonNull(label, "label").strip();
        if (label.isEmpty()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (label.length() > MAX_LABEL_LENGTH) {
            throw new IllegalArgumentException(
                "label must not exceed " + MAX_LABEL_LENGTH + " characters"
            );
        }
        if (label.chars().anyMatch(character -> Character.isISOControl(character))) {
            throw new IllegalArgumentException("label must not contain control characters");
        }
    }

    /**
     * Creates validated options for the supplied history label.
     *
     * @param label user-visible authoring transaction label
     * @return immutable validated options
     */
    public static AuthoringTransactionOptions of(final String label) {
        return new AuthoringTransactionOptions(label);
    }
}
