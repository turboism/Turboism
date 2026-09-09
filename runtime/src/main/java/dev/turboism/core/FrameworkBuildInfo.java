package dev.turboism.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Properties;

/** Immutable identity of the installed framework. Never consults an update server or environment. */
public record FrameworkBuildInfo(
    String version, Channel channel, OptionalLong buildNumber,
    String sourceRevision, String buildKind, boolean dirty
) {
    private static final String RESOURCE = "/META-INF/turboism/framework-version.properties";
    private static final long MAX_BUILD_NUMBER = 9_007_199_254_740_991L;

    public enum Channel {
        STABLE, BETA, NIGHTLY, UNKNOWN;
        public String wireName() { return name().toLowerCase(Locale.ROOT); }
        static Channel parse(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid build channel", invalid); }
        }
    }

    public FrameworkBuildInfo {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(buildNumber, "buildNumber");
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        Objects.requireNonNull(buildKind, "buildKind");
    }

    private static final class Loaded {
        private static final FrameworkBuildInfo VALUE = load();
    }

    /** Package identity, not the user's independently selected future update channel. */
    public static FrameworkBuildInfo current() { return Loaded.VALUE; }
    public boolean isLocalBuild() { return buildKind.equals("local"); }
    public String displayVersion() {
        if (channel == Channel.UNKNOWN) return version;
        String identity = buildNumber.isPresent() ? "Build " + buildNumber.getAsLong()
            : isLocalBuild() ? "local" : "unrecorded build";
        return version + " (" + channel.wireName() + ", " + identity + (dirty ? ", dirty" : "") + ")";
    }

    public static FrameworkBuildInfo fromProperties(Properties p) {
        Objects.requireNonNull(p, "properties");
        String version = p.getProperty("version", "").trim();
        if (!version.matches("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)(?:-[A-Za-z0-9.-]+)?") || version.length() > 120) {
            throw new IllegalArgumentException("Invalid embedded framework version");
        }
        String withoutSnapshot = version.endsWith("-SNAPSHOT") ? version.substring(0, version.length() - 9) : version;
        Channel inferred = withoutSnapshot.contains("-0.nightly.") ? Channel.NIGHTLY
            : withoutSnapshot.contains("-") ? Channel.BETA : Channel.STABLE;
        Channel channel = Channel.parse(p.getProperty("channel", inferred.wireName()).trim());
        if (channel != inferred) throw new IllegalArgumentException("Version/channel mismatch");
        String rawNumber = p.getProperty("buildNumber", "").trim();
        OptionalLong number = OptionalLong.empty();
        if (!rawNumber.isEmpty()) {
            if (!rawNumber.matches("[1-9]\\d{0,15}")) throw new IllegalArgumentException("Invalid build number");
            long parsed;
            try { parsed = Long.parseLong(rawNumber); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid build number", invalid); }
            if (parsed >= MAX_BUILD_NUMBER) throw new IllegalArgumentException("Build number exceeds protocol bound");
            number = OptionalLong.of(parsed);
        }
        String source = p.getProperty("sourceRevision", "unknown").trim();
        String kind = p.getProperty("buildKind", "legacy").trim();
        String rawDirty = p.getProperty("dirty", "false").trim();
        if (!kind.matches("local|ci|legacy") || !rawDirty.matches("true|false")) throw new IllegalArgumentException("Invalid build provenance");
        if (!(source.equals("unknown") || source.matches("[a-f0-9]{40}"))) throw new IllegalArgumentException("Invalid source revision");
        if (number.isPresent()) {
            if (!kind.equals("ci") || !source.matches("[a-f0-9]{40}") || rawDirty.equals("true")) throw new IllegalArgumentException("Numbered build has no clean source identity");
            String core = "(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)";
            String pattern = switch (channel) {
                case STABLE -> core;
                case BETA -> core + "-(?:alpha|beta|rc)\\.(?:0|[1-9]\\d*)";
                case NIGHTLY -> core + "-0\\.nightly\\." + number.getAsLong();
                default -> "(?!)";
            };
            if (!version.matches(pattern)) throw new IllegalArgumentException("Version/build number mismatch");
        } else if (kind.equals("ci")) {
            throw new IllegalArgumentException("Numbered CI identity is missing its build number");
        }
        return new FrameworkBuildInfo(version, channel, number, source, kind, Boolean.parseBoolean(rawDirty));
    }

    private static FrameworkBuildInfo load() {
        try (InputStream input = FrameworkBuildInfo.class.getResourceAsStream(RESOURCE)) {
            if (input != null) {
                Properties p = new Properties(); p.load(input);
                return fromProperties(p);
            }
        } catch (IOException | IllegalArgumentException invalid) {
            // Do not invent a version when a missing/corrupt package cannot establish its identity.
        }
        return new FrameworkBuildInfo("unknown", Channel.UNKNOWN, OptionalLong.empty(), "unknown", "unknown", false);
    }
}
