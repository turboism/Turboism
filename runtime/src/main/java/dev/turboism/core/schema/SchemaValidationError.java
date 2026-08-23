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

    /** @return the stable machine-readable error code validators and tests match on. */
    public String code() {
        return code;
    }

    /** @return how serious this finding is; only ERROR blocks the validated artifact. */
    public Severity severity() {
        return severity;
    }

    /** @return the human-readable explanation of what the validator rejected. */
    public String message() {
        return message;
    }

    /**
     * @return the location inside the validated document, empty when the finding is not tied to a
     *     particular node (a null path is normalized to the empty string at construction)
     */
    public String path() {
        return path;
    }

    /**
     * @return the artifact the finding came from, empty when the validator did not attribute one
     *     (a null source is normalized to the empty string at construction)
     */
    public String source() {
        return source;
    }

    /** @return true when the severity is ERROR; WARNING and INFO findings are advisory. */
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
