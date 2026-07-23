package dev.turboism.ui.host;

import java.util.Objects;
import java.util.Optional;

/** Sanitized failure state for the Editor UI host foundation. */
public record EditorUiHostFailure(
    Code code,
    String message,
    Optional<EditorUiFamily> family
) {
    public EditorUiHostFailure {
        code = Objects.requireNonNull(code, "code");
        message = requireText(message, "message");
        family = Objects.requireNonNull(family, "family");
    }

    public static EditorUiHostFailure host(final Code code, final String message) {
        return new EditorUiHostFailure(code, message, Optional.empty());
    }

    public static EditorUiHostFailure family(
        final Code code,
        final String message,
        final EditorUiFamily family
    ) {
        return new EditorUiHostFailure(code, message, Optional.of(Objects.requireNonNull(family, "family")));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public enum Code {
        HOST_UNAVAILABLE,
        HOST_NOT_READY,
        FAMILY_UNAVAILABLE,
        REPLACEMENT_FAILED,
        CLEANUP_FAILED,
        CLOSED
    }
}
