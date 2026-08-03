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

    /** Exact Core tuple pinned by the reviewed artifact profile; never parsed or guessed. */
    public static CoreVersionExpectation reviewedProfile(final String profile) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile) {
            case "5.2", "5.2.0" -> exact(5, 0, 256);
            case "5.3.02", "5.3.2" -> exact(6, 0, 257);
            default -> throw new IllegalArgumentException(
                "unsupported Cubism Core profile: " + profile
            );
        };
    }

    public boolean matches(final CoreRuntimeVersion actual) {
        return exactVersion.equals(Objects.requireNonNull(actual, "actual"));
    }
}
