package dev.turboism.preview.report;

import java.util.Objects;

/**
 * Raised when a preview report document violates the report contract.
 *
 * <p>Every instance carries a non-blank, machine-readable {@link #code()} alongside the human
 * message, so callers and tests can assert on the specific violation rather than matching text.
 * Unchecked because a malformed report is a defect in whatever produced it, not a condition
 * callers are expected to recover from.
 */
public final class PreviewReportValidationException extends RuntimeException {

    private final String code;

    public PreviewReportValidationException(
        final String code,
        final String message
    ) {
        super(Objects.requireNonNull(message, "message"));
        this.code = requireCode(code);
    }

    public PreviewReportValidationException(
        final String code,
        final String message,
        final Throwable cause
    ) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.code = requireCode(code);
    }

    /**
     * @return the stable machine-readable identifier of the violated rule; never blank, and the
     *     value callers should branch or assert on instead of the message text
     */
    public String code() {
        return code;
    }

    private static String requireCode(final String value) {
        Objects.requireNonNull(value, "code");
        if (value.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        return value;
    }
}
