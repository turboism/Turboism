package dev.turboism.sdk.cubism.history;

import java.util.Objects;
import java.util.Optional;

/** Trusted attribution for one semantic history detail. */
public record HistoryOrigin(
    Kind kind,
    Optional<String> producerId,
    Optional<String> operationId
) {

    private static final int MAX_ID_LENGTH = 128;

    public HistoryOrigin {
        kind = Objects.requireNonNull(kind, "kind");
        producerId = normalizedOptional(producerId, "producerId");
        operationId = normalizedOptional(operationId, "operationId");
        if (kind == Kind.HOST_UNATTRIBUTED && (producerId.isPresent() || operationId.isPresent())) {
            throw new IllegalArgumentException(
                "HOST_UNATTRIBUTED must not claim producer or operation identity"
            );
        }
    }

    /** Creates an origin attributed to one Turboism producer operation. */
    public static HistoryOrigin turboism(
        final String producerId,
        final String operationId
    ) {
        return new HistoryOrigin(
            Kind.TURBOISM,
            Optional.of(producerId),
            Optional.of(operationId)
        );
    }

    /** Creates a conservative origin for an unattributed host operation. */
    public static HistoryOrigin hostUnattributed() {
        return new HistoryOrigin(Kind.HOST_UNATTRIBUTED, Optional.empty(), Optional.empty());
    }

    private static Optional<String> normalizedOptional(
        final Optional<String> value,
        final String fieldName
    ) {
        return Objects.requireNonNull(value, fieldName)
            .map(item -> normalizedId(item, fieldName));
    }

    private static String normalizedId(final String value, final String fieldName) {
        final String normalized = Objects.requireNonNull(value, fieldName).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        if (normalized.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(
                fieldName + " must not exceed " + MAX_ID_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(fieldName + " must not contain control characters");
        }
        return normalized;
    }

    public enum Kind {
        TURBOISM,
        HOST_UNATTRIBUTED
    }
}
