package dev.turboism.distribution.record;

import dev.turboism.internal.Requirements;
import java.util.Objects;

record ProtocolValidationIssue(String code, String message, String path) {
    ProtocolValidationIssue {
        code = Requirements.text(code, "code");
        message = Requirements.text(message, "message");
        path = Objects.requireNonNull(path, "path");
    }
}
