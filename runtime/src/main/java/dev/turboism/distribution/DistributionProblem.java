package dev.turboism.distribution;

import java.util.Objects;

public record DistributionProblem(String code, String message, String path) {
    public DistributionProblem {
        code = require(code, "code");
        message = require(message, "message");
        path = Objects.requireNonNull(path, "path");
    }

    private static String require(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
