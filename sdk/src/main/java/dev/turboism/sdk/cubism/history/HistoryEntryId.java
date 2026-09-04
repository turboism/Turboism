package dev.turboism.sdk.cubism.history;

import java.util.Objects;

/** Stable, Turboism-owned identity of one native Cubism Undo-history entry. */
public record HistoryEntryId(String value) {

    /** Maximum public length of an opaque history-entry identity. */
    public static final int MAX_LENGTH = 128;

    public HistoryEntryId {
        value = Objects.requireNonNull(value, "value").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                "value must not exceed " + MAX_LENGTH + " characters"
            );
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("value must not contain control characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
