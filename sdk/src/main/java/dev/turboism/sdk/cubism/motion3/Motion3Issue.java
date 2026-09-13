package dev.turboism.sdk.cubism.motion3;

import java.util.Objects;

/** One finding reported by {@link Motion3Validator}. */
public record Motion3Issue(Severity severity, String path, String message) {

    public enum Severity {
        /** The document violates the motion3 format and may fail to load. */
        ERROR,
        /** The document is structurally sound but internally inconsistent. */
        WARNING
    }

    public Motion3Issue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
    }

    @Override public String toString() {
        return severity + " " + path + ": " + message;
    }
}
