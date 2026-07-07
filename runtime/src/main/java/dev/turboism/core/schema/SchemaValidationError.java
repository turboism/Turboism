package dev.turboism.core.schema;

import java.util.Objects;

/**
 * Structured validation error returned by all schema validators.
 */
public final class SchemaValidationError {

    private final String code;
    private final Severity severity;
    private final String message;
    private final String path;
    private final String source;

    public SchemaValidationError(String code, Severity severity, String message, String path, String source) {
        this.code = Objects.requireNonNull(code, "code");
        this.severity = Objects.requireNonNull(severity, "severity");
        this.message = Objects.requireNonNull(message, "message");
        this.path = path != null ? path : "";
        this.source = source != null ? source : "";
    }

    public String code() {
        return code;
    }

    public Severity severity() {
        return severity;
    }

    public String message() {
        return message;
    }

    public String path() {
        return path;
    }

    public String source() {
        return source;
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    @Override
    public String toString() {
        return String.format("%s[%s] %s (path=%s, source=%s)", code, severity, message, path, source);
    }

    public enum Severity {
        ERROR, WARNING, INFO
    }
}
