package dev.turboism.sdk.cubism.history;

import java.util.Objects;

/** One exact parameter key-point coordinate used to address a parameter-bound keyform. */
public record HistoryParameterCoordinate(
    HistoryTarget parameter,
    String value
) {

    private static final int MAX_VALUE_LENGTH = 256;

    public HistoryParameterCoordinate {
        parameter = Objects.requireNonNull(parameter, "parameter");
        if (!"PARAMETER".equals(parameter.type())) {
            throw new IllegalArgumentException("parameter target type must be PARAMETER");
        }
        value = normalizedValue(value);
    }

    private static String normalizedValue(final String value) {
        final String normalized = Objects.requireNonNull(value, "value").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        if (normalized.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException(
                "value must not exceed " + MAX_VALUE_LENGTH + " characters"
            );
        }
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("value must not contain control characters");
        }
        return normalized;
    }
}
