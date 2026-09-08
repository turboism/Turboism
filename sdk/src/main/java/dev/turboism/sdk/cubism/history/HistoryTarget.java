package dev.turboism.sdk.cubism.history;

import java.util.Objects;
import java.util.Optional;

/** Stable Turboism-owned projection of one object affected by a history entry. */
public record HistoryTarget(
    String type,
    Optional<String> id,
    Optional<String> displayName
) {

    private static final int MAX_TYPE_LENGTH = 64;
    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_DISPLAY_NAME_LENGTH = 256;

    public HistoryTarget {
        type = normalizedToken(type, "type", MAX_TYPE_LENGTH);
        id = Objects.requireNonNull(id, "id")
            .map(value -> normalizedText(value, "id", MAX_ID_LENGTH));
        displayName = Objects.requireNonNull(displayName, "displayName")
            .map(value -> normalizedText(value, "displayName", MAX_DISPLAY_NAME_LENGTH));
    }
    private static String normalizedToken(
        final String value,
        final String fieldName,
        final int maxLength
    ) {
        final String normalized = normalizedText(value, fieldName, maxLength);
        if (!normalized.matches("[A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)*")) {
            throw new IllegalArgumentException(fieldName + " must be a normalized uppercase token");
        }
        return normalized;
    }

    private static String normalizedText(
        final String value,
        final String fieldName,
        final int maxLength
    ) {
        final String normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                fieldName + " must not exceed " + maxLength + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        return normalized;
    }
}
