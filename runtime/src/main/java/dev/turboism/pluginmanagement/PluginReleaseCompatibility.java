package dev.turboism.pluginmanagement;

import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogDependency;
import dev.turboism.pluginmanagement.PluginCatalog.CatalogRelease;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Single source of truth for whether a catalog release may run on this installation.
 *
 * <p>The market browser and the background auto-updater must agree: a release the market offers
 * must be one the updater would also accept, and a release the market hides must never be staged
 * silently. Both callers therefore evaluate through this class instead of repeating the rules.</p>
 */
public final class PluginReleaseCompatibility {

    /** Catalog platform identifier for the Windows x64 distribution. */
    public static final String PLATFORM_WINDOWS_X64 = "windows-x64";

    /** The only platform this distribution ships; catalog releases must declare it. */
    public static final String REQUIRED_PLATFORM = PLATFORM_WINDOWS_X64;

    private PluginReleaseCompatibility() {}

    /** Rule that rejected a release. */
    public enum Rejection {
        /** Declared {@code turboismApi} range excludes the running framework API. */
        TURBOISM_API,
        /** Release channel is not offered to the installed framework channel. */
        CHANNEL,
        /** Release needs a Cubism Editor host and none is attached. */
        CUBISM_HOST,
        /** Attached Cubism Editor version is outside the declared support list. */
        CUBISM_VERSION,
        /** Release does not target this distribution platform. */
        PLATFORM,
        /** A required inter-plugin dependency is missing or out of range. */
        DEPENDENCY
    }

    /**
     * Outcome of one compatibility evaluation.
     *
     * @param rejection the first failing rule; empty when the release is admitted
     * @param detail human-readable explanation; empty when the release is admitted
     */
    public record Verdict(Optional<Rejection> rejection, Optional<String> detail) {

        public Verdict {
            rejection = Objects.requireNonNull(rejection, "rejection");
            detail = Objects.requireNonNull(detail, "detail");
        }

        /** Returns a verdict that admits the release. */
        public static Verdict admitted() {
            return new Verdict(Optional.empty(), Optional.empty());
        }

        /** Returns a verdict that rejects the release for the given rule. */
        public static Verdict rejected(final Rejection rejection, final String detail) {
            return new Verdict(Optional.of(Objects.requireNonNull(rejection, "rejection")), Optional.of(detail));
        }

        /** Returns {@code true} when no rule rejected the release. */
        public boolean compatible() {
            return rejection.isEmpty();
        }
    }

    /**
     * Evaluates the environment rules for one release.
     *
     * @param release catalog release under evaluation
     * @param runtimeApi framework API version of the running installation
     * @param channel framework channel the installation was packaged for
     * @param cubismVersion attached Cubism Editor version, or empty when no host is attached
     * @return the first failing rule, or an admitted verdict
     */
    public static Verdict evaluate(
            final CatalogRelease release,
            final PluginVersion runtimeApi,
            final FrameworkBuildInfo.Channel channel,
            final Optional<String> cubismVersion) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(runtimeApi, "runtimeApi");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(cubismVersion, "cubismVersion");

        final VersionRange apiRange;
        try {
            apiRange = VersionRange.parse(release.turboismApi());
        } catch (IllegalArgumentException invalid) {
            return Verdict.rejected(Rejection.TURBOISM_API, "Declared Turboism API range is invalid");
        }
        if (!apiRange.contains(runtimeApi)) {
            return Verdict.rejected(
                    Rejection.TURBOISM_API,
                    "Requires Turboism API " + release.turboismApi() + " (current is " + runtimeApi + ")");
        }
        if (!channelAccepts(channel, release.channel())) {
            return Verdict.rejected(
                    Rejection.CHANNEL,
                    "Release channel " + release.channel() + " is not offered to a " + channel.wireName()
                            + " installation");
        }
        if (!release.platforms().contains(REQUIRED_PLATFORM)) {
            return Verdict.rejected(Rejection.PLATFORM, "Release does not support platform " + REQUIRED_PLATFORM);
        }
        if (release.requiresCubism()) {
            if (cubismVersion.isEmpty()) {
                return Verdict.rejected(Rejection.CUBISM_HOST, "Requires Cubism host");
            }
            if (!release.cubismVersions().contains(cubismVersion.orElseThrow())) {
                return Verdict.rejected(
                        Rejection.CUBISM_VERSION,
                        "Cubism " + cubismVersion.orElseThrow() + " is not in supported versions");
            }
        }
        return Verdict.admitted();
    }

    /**
     * Evaluates required inter-plugin dependencies against the versions available after installation.
     *
     * @param release catalog release under evaluation
     * @param availableVersions plugin id to version that will be present once the release installs
     * @return the first unsatisfied required dependency, or an admitted verdict
     */
    public static Verdict evaluateDependencies(
            final CatalogRelease release, final Map<String, PluginVersion> availableVersions) {
        Objects.requireNonNull(release, "release");
        Objects.requireNonNull(availableVersions, "availableVersions");

        for (CatalogDependency dependency : release.dependencies()) {
            if (!dependency.isRequired()) continue;
            final PluginVersion available = availableVersions.get(dependency.id());
            if (available == null) {
                return Verdict.rejected(
                        Rejection.DEPENDENCY, "Requires plugin " + dependency.id() + " " + dependency.version());
            }
            final VersionRange range;
            try {
                range = VersionRange.parse(dependency.version());
            } catch (IllegalArgumentException invalid) {
                return Verdict.rejected(
                        Rejection.DEPENDENCY,
                        "Dependency " + dependency.id() + " declares an invalid range " + dependency.version());
            }
            if (!range.contains(available)) {
                return Verdict.rejected(
                        Rejection.DEPENDENCY,
                        "Requires plugin " + dependency.id() + " " + dependency.version() + " (installed is "
                                + available + ")");
            }
        }
        return Verdict.admitted();
    }

    /**
     * Returns whether a framework channel is offered releases published on a catalog channel.
     *
     * @param frameworkChannel installed framework channel
     * @param releaseChannel catalog release channel
     * @return {@code true} when the installation may install or update to that release
     */
    public static boolean channelAccepts(
            final FrameworkBuildInfo.Channel frameworkChannel, final String releaseChannel) {
        Objects.requireNonNull(frameworkChannel, "frameworkChannel");
        return switch (frameworkChannel) {
            case BETA, NIGHTLY ->
                CatalogRelease.CHANNEL_STABLE.equals(releaseChannel)
                        || CatalogRelease.CHANNEL_PREVIEW.equals(releaseChannel);
            case STABLE, UNKNOWN -> CatalogRelease.CHANNEL_STABLE.equals(releaseChannel);
        };
    }
}
