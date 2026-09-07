package dev.turboism.sdk.cubism.history;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Trusted semantic location at which one history change applies. */
public record HistoryEditContext(
    Kind kind,
    Optional<String> formId,
    List<HistoryParameterCoordinate> coordinates
) {

    private static final int MAX_FORM_ID_LENGTH = 256;
    private static final int MAX_COORDINATES = 64;

    public HistoryEditContext {
        kind = Objects.requireNonNull(kind, "kind");
        formId = Objects.requireNonNull(formId, "formId")
            .map(HistoryEditContext::normalizedFormId);
        coordinates = List.copyOf(Objects.requireNonNull(coordinates, "coordinates"));
        if (coordinates.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("coordinates must not contain null values");
        }
        if (coordinates.size() > MAX_COORDINATES) {
            throw new IllegalArgumentException(
                "coordinates must not exceed " + MAX_COORDINATES + " items"
            );
        }
        validateScope(kind, formId, coordinates);
        validateUniqueParameters(coordinates);
    }

    private static String normalizedFormId(final String value) {
        final String normalized = Objects.requireNonNull(value, "formId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("formId must not be blank");
        }
        if (normalized.length() > MAX_FORM_ID_LENGTH) {
            throw new IllegalArgumentException(
                "formId must not exceed " + MAX_FORM_ID_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("formId must not contain control characters");
        }
        return normalized;
    }

    private static void validateScope(
        final Kind kind,
        final Optional<String> formId,
        final List<HistoryParameterCoordinate> coordinates
    ) {
        switch (kind) {
            case OBJECT, DOCUMENT, UNKNOWN -> {
                if (formId.isPresent() || !coordinates.isEmpty()) {
                    throw new IllegalArgumentException(
                        kind + " context must not contain form identity or parameter coordinates"
                    );
                }
            }
            case DEFAULT_FORM -> {
                if (!coordinates.isEmpty()) {
                    throw new IllegalArgumentException(
                        "DEFAULT_FORM context must not contain parameter coordinates"
                    );
                }
            }
            case KEYFORM -> {
                // An empty tuple can represent a verified keyform with incomplete coordinates,
                // but the enclosing detail must then remain PARTIAL.
            }
        }
    }

    private static void validateUniqueParameters(
        final List<HistoryParameterCoordinate> coordinates
    ) {
        final Set<HistoryTarget> observed = new HashSet<>();
        for (final HistoryParameterCoordinate coordinate : coordinates) {
            if (!observed.add(coordinate.parameter())) {
                throw new IllegalArgumentException(
                    "coordinates must not contain the same parameter more than once"
                );
            }
        }
    }

    /** Scope of a semantic history change. */
    public enum Kind {
        OBJECT,
        DEFAULT_FORM,
        KEYFORM,
        DOCUMENT,
        UNKNOWN
    }
}
