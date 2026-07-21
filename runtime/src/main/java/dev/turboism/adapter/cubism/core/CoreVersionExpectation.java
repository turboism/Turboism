package dev.turboism.adapter.cubism.core;

import java.util.Objects;

/**
 * Exact runtime version expectation supplied by reviewed profile evidence.
 *
 * <p>The expectation is intentionally independent from an Editor/Core artifact profile label.
 * Admission must never guess a runtime tuple by parsing a label such as {@code 5.3.02}.</p>
 */
public record CoreVersionExpectation(CoreRuntimeVersion exactVersion) {

    public CoreVersionExpectation {
        exactVersion = Objects.requireNonNull(exactVersion, "exactVersion");
    }

    public static CoreVersionExpectation exact(
        final int major,
        final int minor,
        final int patch
    ) {
        return new CoreVersionExpectation(new CoreRuntimeVersion(major, minor, patch));
    }

    public boolean matches(final CoreRuntimeVersion actual) {
        return exactVersion.equals(Objects.requireNonNull(actual, "actual"));
    }
}
