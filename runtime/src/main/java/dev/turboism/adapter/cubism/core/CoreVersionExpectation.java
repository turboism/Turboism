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
    public static CoreVersionExpectation exact(final int major, final int minor, final int patch) {
        return new CoreVersionExpectation(new CoreRuntimeVersion(major, minor, patch));
    }

    /**
     * Exact Core tuple pinned by the reviewed artifact profile; never parsed or guessed.
     *
     * <p>Every entry is the tuple the real host reported through the production SDK during
     * the recorded owned-MOC/core-acquisition runs (see
     * {@code validation/cubism-version-matrix} evidence properties): 5.0.x and 5.1.x Cores
     * report {@code 5.0.0}, 5.2.x report {@code 5.0.256}, the byte-identical 5.3.00-5.3.03
     * Core reports {@code 6.0.257}, and 5.3.04 reports {@code 6.0.513}.</p>
     */
    public static CoreVersionExpectation reviewedProfile(final String profile) {
        Objects.requireNonNull(profile, "profile");
        return switch (profile) {
            case "5.0.00",
                    "5.0.01",
                    "5.0.02",
                    "5.0.03",
                    "5.0.04",
                    "5.0.05",
                    "5.0.06",
                    "5.1.00",
                    "5.1.01",
                    "5.1.02",
                    "5.1.03" -> exact(5, 0, 0);
            case "5.2.00", "5.2.01", "5.2.02", "5.2.03" -> exact(5, 0, 256);
            case "5.3.00", "5.3.01", "5.3.02", "5.3.03" -> exact(6, 0, 257);
            case "5.3.04" -> exact(6, 0, 513);
            default -> throw new IllegalArgumentException("unsupported Cubism Core profile: " + profile);
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
