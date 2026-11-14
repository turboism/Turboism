package dev.turboism.ui.host;

import dev.turboism.internal.Requirements;
import java.util.Objects;
import java.util.Optional;

/** Sanitized failure state for the Editor UI host foundation. */
public record EditorUiHostFailure(Code code, String message, Optional<EditorUiFamily> family) {
    public EditorUiHostFailure {
        code = Objects.requireNonNull(code, "code");
        message = Requirements.text(message, "message");
        family = Objects.requireNonNull(family, "family");
    }

    /**
     * @param code why the host left its healthy path
     * @param message runtime-authored explanation; must not be blank
     * @return a failure attributed to the host as a whole rather than to one UI family
     */
    public static EditorUiHostFailure host(final Code code, final String message) {
        return new EditorUiHostFailure(code, message, Optional.empty());
    }

    /**
     * @param code why the family left its healthy path
     * @param message runtime-authored explanation; must not be blank
     * @param family the single UI family affected; the rest of the host is unaffected
     * @return a failure scoped to one UI family
     * @throws NullPointerException if {@code family} is null
     */
    public static EditorUiHostFailure family(final Code code, final String message, final EditorUiFamily family) {
        return new EditorUiHostFailure(code, message, Optional.of(Objects.requireNonNull(family, "family")));
    }

    /** Why the Editor UI host left its healthy path. */
    public enum Code {
        /** No host connection exists at all. */
        HOST_UNAVAILABLE,
        /** The host is connected but not yet ready for UI integration. */
        HOST_NOT_READY,
        /** One UI family is unavailable on the connected host. */
        FAMILY_UNAVAILABLE,
        /** Replacing a live native registration failed. */
        REPLACEMENT_FAILED,
        /** Tearing down native state failed. */
        CLEANUP_FAILED,
        /** The host integration has been closed. */
        CLOSED
    }
}
