package dev.turboism.distribution.record;

import java.util.Objects;

record ProtocolValidationIssue(String code, String message, String path) {
    ProtocolValidationIssue {
        code = requireText(code, "code");
        message = requireText(message, "message");
        path = Objects.requireNonNull(path, "path");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
