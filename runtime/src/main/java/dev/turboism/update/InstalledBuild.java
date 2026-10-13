package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.core.FrameworkBuildInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Installed product identity used for update comparison and display.
 *
 * <p>The numeric version is empty when the packaged identity has no canonical
 * numeric core; an unrecorded historical build never receives an invented build
 * number.</p>
 */
public record InstalledBuild(
        Optional<UpdateVersion> version, OptionalLong buildNumber, String display, String channel, String variant) {
    public static final String VARIANT_FULL = "full";
    public static final String VARIANT_THIN = "thin";
    public static final String METADATA_FILE_NAME = "installed-build.json";

    public static final Set<String> FIRST_PARTY_PLUGIN_JARS = Set.of(
            "acp.jar",
            "atlas-maxrects-bssf.jar",
            "boundingbox-warp-mirror.jar",
            "clipmask-viewer.jar",
            "command-palette.jar",
            "cubism-tab-filter.jar",
            "external-psd-edit.jar",
            "history-panel.jar",
            "mcp.jar",
            "mesh-edit-mirror-axis-enhance.jar",
            "model-import-3d.jar",
            "palette-label-style.jar",
            "parameter-batch-transfer.jar",
            "perf-stats.jar",
            "physics-editor.jar",
            "protected-export.jar",
            "psd-clip-mask-import.jar",
            "recent-preview.jar",
            "scene-palette-enhancer.jar",
            "selection-brush.jar",
            "texture-atlas-stats.jar",
            "ui-theme.jar",
            "warp-deformer-alt-symmetry.jar",
            "webdav-backup.jar");

    /** Creates an installed build identity, defaulting variant to full. */
    public InstalledBuild(
            final Optional<UpdateVersion> version,
            final OptionalLong buildNumber,
            final String display,
            final String channel) {
        this(version, buildNumber, display, channel, VARIANT_FULL);
    }

    /** Creates an installed build identity, inferring the release channel from {@code display}. */
    public InstalledBuild(final Optional<UpdateVersion> version, final OptionalLong buildNumber, final String display) {
        this(version, buildNumber, display, inferChannel(display), VARIANT_FULL);
    }

    public InstalledBuild {
        version = Objects.requireNonNull(version, "version");
        buildNumber = Objects.requireNonNull(buildNumber, "buildNumber");
        display = Objects.requireNonNull(display, "display");
        channel = Objects.requireNonNull(channel, "channel");
        variant = Objects.requireNonNull(variant, "variant");
        if (display.isBlank()) throw new IllegalArgumentException("display must not be blank");
        if (!UpdateDiscovery.isSupportedChannel(channel)) {
            throw new IllegalArgumentException("channel is unsupported: " + channel);
        }
        if (!VARIANT_FULL.equalsIgnoreCase(variant) && !VARIANT_THIN.equalsIgnoreCase(variant)) {
            throw new IllegalArgumentException("variant must be full or thin: " + variant);
        }
        variant = variant.toLowerCase(Locale.ROOT);
        if (buildNumber.isPresent() && buildNumber.getAsLong() <= 0) {
            throw new IllegalArgumentException("buildNumber must be positive");
        }
    }

    /** Reads the packaged framework identity; never consults the network. */
    public static InstalledBuild current() {
        return from(FrameworkBuildInfo.current());
    }

    /** Projects the packaged framework identity with explicit home directory to detect installed-build metadata. */
    public static InstalledBuild forHome(final Path home) {
        return from(FrameworkBuildInfo.current(), home);
    }

    /** Projects the packaged framework identity into the update-comparison view, defaulting variant to full. */
    public static InstalledBuild from(final FrameworkBuildInfo info) {
        return from(info, null);
    }

    /** Projects the packaged framework identity into the update-comparison view, resolving variant from home. */
    public static InstalledBuild from(final FrameworkBuildInfo info, final Path home) {
        Objects.requireNonNull(info, "info");
        return new InstalledBuild(
                numericCore(info.version()),
                info.buildNumber(),
                info.displayVersion(),
                channelFor(info.channel()),
                inferVariant(home));
    }

    /** Detects whether the installed variant is full or thin from metadata or plugin presence. */
    public static String inferVariant(final Path home) {
        if (home == null) return VARIANT_FULL;
        final Path metadata = home.resolve(METADATA_FILE_NAME);
        if (Files.isRegularFile(metadata)) {
            try {
                final JsonNode tree = UpdateFileSupport.readObject(home, metadata);
                if (tree != null) {
                    final JsonNode variantNode = tree.get("variant");
                    if (variantNode != null
                            && variantNode.isTextual()
                            && !variantNode.textValue().isBlank()) {
                        final String v = variantNode.textValue().trim().toLowerCase(Locale.ROOT);
                        if (VARIANT_FULL.equals(v) || VARIANT_THIN.equals(v)) {
                            return v;
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // If metadata is corrupt, fall through to inference by plugins presence.
            }
        }
        // When absent, infer full if first-party plugins are present; otherwise thin.
        final Path plugins = home.resolve("plugins");
        if (Files.isDirectory(plugins)) {
            for (final String jarName : FIRST_PARTY_PLUGIN_JARS) {
                if (Files.exists(plugins.resolve(jarName))) {
                    return VARIANT_FULL;
                }
            }
        }
        return VARIANT_THIN;
    }

    /** Deterministic construction seam used by focused tests. */
    public static InstalledBuild of(final String version, final OptionalLong buildNumber, final String display) {
        return new InstalledBuild(numericCore(version), buildNumber, display, inferChannel(version), VARIANT_FULL);
    }

    /** Deterministic construction seam with an explicit release channel used by focused tests. */
    public static InstalledBuild of(
            final String version, final String channel, final OptionalLong buildNumber, final String display) {
        return new InstalledBuild(numericCore(version), buildNumber, display, channel, VARIANT_FULL);
    }

    /** Deterministic construction seam with an explicit variant used by focused tests. */
    public static InstalledBuild of(
            final String version,
            final String channel,
            final String variant,
            final OptionalLong buildNumber,
            final String display) {
        return new InstalledBuild(numericCore(version), buildNumber, display, channel, variant);
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
