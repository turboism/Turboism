package dev.turboism.distribution;

import java.util.Objects;

/**
 * A single reason a distribution package was rejected by an inspector.
 *
 * <p>Immutable and exception-free: inspectors translate every failure - validation, I/O, or
 * unexpected - into one of these before returning a rejection, so callers never observe a stack
 * trace produced by untrusted package contents.
 *
 * @param code stable machine-readable rejection code (for example {@code PACKAGE_TOO_LARGE}),
 *             never blank
 * @param message short human-readable explanation, never blank
 * @param path location the problem is attributed to - the package path or an archive entry name;
 *             may be empty but never {@code null}
 */
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
