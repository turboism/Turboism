package dev.turboism.update;

import dev.turboism.core.FrameworkBuildInfo;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Installed product identity used for update comparison and display.
 *
 * <p>The numeric version is empty when the packaged identity has no canonical
 * numeric core; an unrecorded historical build never receives an invented build
 * number.</p>
 */
public record InstalledBuild(
    Optional<UpdateVersion> version,
    OptionalLong buildNumber,
    String display
) {
    public InstalledBuild {
        version = Objects.requireNonNull(version, "version");
        buildNumber = Objects.requireNonNull(buildNumber, "buildNumber");
        display = Objects.requireNonNull(display, "display");
        if (display.isBlank()) throw new IllegalArgumentException("display must not be blank");
        if (buildNumber.isPresent() && buildNumber.getAsLong() <= 0) {
            throw new IllegalArgumentException("buildNumber must be positive");
        }
    }

    /** Reads the packaged framework identity; never consults the network. */
    public static InstalledBuild current() {
        return from(FrameworkBuildInfo.current());
    }

    /** Projects the packaged framework identity into the update-comparison view. */
    public static InstalledBuild from(final FrameworkBuildInfo info) {
        Objects.requireNonNull(info, "info");
        return new InstalledBuild(
            numericCore(info.version()),
            info.buildNumber(),
            info.displayVersion()
        );
    }

    /** Deterministic construction seam used by focused tests. */
    public static InstalledBuild of(
        final String version,
        final OptionalLong buildNumber,
        final String display
    ) {
        return new InstalledBuild(numericCore(version), buildNumber, display);
    }

    /** Returns the exact installed version text used in reminders and diagnostics. */
    public String versionText() {
        return display;
    }

    private static Optional<UpdateVersion> numericCore(final String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(UpdateVersion.parseInstalled(value.trim()));
        } catch (IllegalArgumentException unsupported) {
            return Optional.empty();
        }
    }
}
