package dev.turboism.preview.report;

import java.util.Objects;

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
