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
        Optional<UpdateVersion> version, OptionalLong buildNumber, String display, String channel) {
    /** Creates an installed build identity, inferring the release channel from {@code display}. */
    public InstalledBuild(final Optional<UpdateVersion> version, final OptionalLong buildNumber, final String display) {
        this(version, buildNumber, display, inferChannel(display));
    }

    public InstalledBuild {
        version = Objects.requireNonNull(version, "version");
        buildNumber = Objects.requireNonNull(buildNumber, "buildNumber");
        display = Objects.requireNonNull(display, "display");
        channel = Objects.requireNonNull(channel, "channel");
        if (display.isBlank()) throw new IllegalArgumentException("display must not be blank");
        if (!UpdateDiscovery.isSupportedChannel(channel)) {
            throw new IllegalArgumentException("channel is unsupported: " + channel);
        }
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
                numericCore(info.version()), info.buildNumber(), info.displayVersion(), channelFor(info.channel()));
    }

    /** Deterministic construction seam used by focused tests. */
    public static InstalledBuild of(final String version, final OptionalLong buildNumber, final String display) {
        return new InstalledBuild(numericCore(version), buildNumber, display, inferChannel(version));
    }

    /** Deterministic construction seam with an explicit release channel used by focused tests. */
    public static InstalledBuild of(
            final String version, final String channel, final OptionalLong buildNumber, final String display) {
        return new InstalledBuild(numericCore(version), buildNumber, display, channel);
    }

    /** Maps a packaged build channel to the release-discovery channel wire name. */
    public static String channelFor(final FrameworkBuildInfo.Channel channel) {
        Objects.requireNonNull(channel, "channel");
        return switch (channel) {
            case BETA -> UpdateDiscovery.CHANNEL_BETA;
            case NIGHTLY -> UpdateDiscovery.CHANNEL_NIGHTLY;
            case STABLE, UNKNOWN -> UpdateDiscovery.CHANNEL_STABLE;
        };
    }

    private static String inferChannel(final String value) {
        if (value == null) return UpdateDiscovery.CHANNEL_STABLE;
        if (value.contains("-0.nightly.") || value.contains("(nightly,")) {
            return UpdateDiscovery.CHANNEL_NIGHTLY;
        }
        if (value.contains("-alpha.")
                || value.contains("-beta.")
                || value.contains("-rc.")
                || value.contains("(beta,")) {
            return UpdateDiscovery.CHANNEL_BETA;
        }
        return UpdateDiscovery.CHANNEL_STABLE;
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
