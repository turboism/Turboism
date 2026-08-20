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

    /**
     * @param major Core runtime major component
     * @param minor Core runtime minor component
     * @param patch Core runtime patch component
     * @return an expectation pinned to exactly that runtime tuple
     */
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
            case "5.2.03" -> exact(5, 0, 256);
            case "5.3.02" -> exact(6, 0, 257);
            default -> throw new IllegalArgumentException(
                "unsupported Cubism Core profile: " + profile
            );
        };
    }

    /**
     * @param actual runtime version probed from the loaded Core
     * @return true only on an exact tuple equality; no range or compatibility rule is applied
     * @throws NullPointerException if {@code actual} is null
     */
    public boolean matches(final CoreRuntimeVersion actual) {
        return exactVersion.equals(Objects.requireNonNull(actual, "actual"));
    }
}
