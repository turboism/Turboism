package dev.turboism.exportsettings;

import dev.turboism.adapter.cubism.profile.CubismExportHostProfile;
import dev.turboism.internal.Requirements;
import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact reviewed selectors for the embedded-model exporter chooser seam.
 *
 * <p>Pinned from classfile observation of {@code com/live2d/cubism/doc/model/exporter/b}: the
 * continuation methods {@code b} and {@code c} each invoke one private chooser once. The
 * installer refuses mismatched tuples, and the transformer fails closed on cardinality drift.</p>
 *
 * <p>Reviewed builds from 5.1.00 through 5.2.03 take the abstract window panel
 * {@code com/live2d/ui/window/X}, while builds from 5.3.00 onward take
 * {@code com/live2d/ui/window/V}. Earlier or unreviewed artifacts yield empty.</p>
 */
public record ProtectedExportChooserProfile(
        String hostVersion,
        StaticSelector exporterOwner,
        StaticSelector moc3Continuation,
        StaticSelector moc3Chooser,
        StaticSelector gatedContinuation,
        StaticSelector gatedChooser) {

    /** Alias of the exporter owner class selector. */
    public static final String EXPORTER_OWNER_ALIAS = "cubism.protected-export.exporter.owner";
    /** Alias of the moc3 export continuation selector. */
    public static final String MOC3_CONTINUATION_ALIAS = "cubism.protected-export.exporter.moc3-continuation";
    /** Alias of the moc3 chooser selector. */
    public static final String MOC3_CHOOSER_ALIAS = "cubism.protected-export.exporter.moc3-chooser";
    /** Alias of the save-gated export continuation selector. */
    public static final String GATED_CONTINUATION_ALIAS = "cubism.protected-export.exporter.gated-continuation";
    /** Alias of the save-gated chooser selector. */
    public static final String GATED_CHOOSER_ALIAS = "cubism.protected-export.exporter.gated-chooser";

    private static final int ACCESS_PRIVATE = 0x0002;
    private static final int FORBID_STATIC = StaticSelector.ACCESS_STATIC;

    private static final String EXPORTER = CubismExportHostProfile.CHOOSER_EXPORTER;
    private static final String WINDOW_PANEL_5_2 = CubismExportHostProfile.SETTINGS_WINDOW_PANEL_5_2;
    private static final String WINDOW_PANEL_5_3 = CubismExportHostProfile.SETTINGS_WINDOW_PANEL_5_3;
    private static final String MODEL_SOURCE = CubismExportHostProfile.SETTINGS_MODEL_SOURCE;
    private static final String FUNCTION2 = "kotlin/jvm/functions/Function2";

    private static final Set<String> WINDOW_PANEL_5_2_VERSIONS = ReviewedVersions.between("5.1.00", "5.2.03");
    private static final Set<String> WINDOW_PANEL_5_3_VERSIONS = ReviewedVersions.since("5.3.00");
    private static final Map<HostArtifactDigest, ProtectedExportChooserProfile> BY_ARTIFACT = buildProfilesByArtifact();

    private static ProtectedExportChooserProfile reviewedProfile(
            final String hostVersion, final String windowPanel, final String mappingIdPrefix) {
        return new ProtectedExportChooserProfile(
                hostVersion,
                new StaticSelector(
                        mappingIdPrefix + "exporter_owner",
                        EXPORTER_OWNER_ALIAS,
                        StaticSelector.Kind.CLASS,
                        EXPORTER,
                        "",
                        "",
                        0,
                        0),
                new StaticSelector(
                        mappingIdPrefix + "exporter_moc3_continuation",
                        MOC3_CONTINUATION_ALIAS,
                        StaticSelector.Kind.METHOD,
                        EXPORTER,
                        "b",
                        "(" + descriptor(windowPanel) + descriptor(MODEL_SOURCE) + descriptor(FUNCTION2) + ")Z",
                        ACCESS_PRIVATE,
                        FORBID_STATIC),
                new StaticSelector(
                        mappingIdPrefix + "exporter_moc3_chooser",
                        MOC3_CHOOSER_ALIAS,
                        StaticSelector.Kind.METHOD,
                        EXPORTER,
                        "a",
                        "(" + descriptor(windowPanel) + "Ljava/lang/String;)Ljava/io/File;",
                        ACCESS_PRIVATE,
                        FORBID_STATIC),
                new StaticSelector(
                        mappingIdPrefix + "exporter_gated_continuation",
                        GATED_CONTINUATION_ALIAS,
                        StaticSelector.Kind.METHOD,
                        EXPORTER,
                        "c",
                        "(" + descriptor(windowPanel) + descriptor(MODEL_SOURCE) + descriptor(FUNCTION2) + ")Z",
                        ACCESS_PRIVATE,
                        FORBID_STATIC),
                new StaticSelector(
                        mappingIdPrefix + "exporter_gated_chooser",
                        GATED_CHOOSER_ALIAS,
                        StaticSelector.Kind.METHOD,
                        EXPORTER,
                        "a",
                        "(Ljava/lang/String;Z" + descriptor(windowPanel) + ")Ljava/io/File;",
                        ACCESS_PRIVATE,
                        FORBID_STATIC));
    }

    public ProtectedExportChooserProfile {
        hostVersion = Requirements.text(hostVersion, "hostVersion");
        exporterOwner = Objects.requireNonNull(exporterOwner, "exporterOwner");
        moc3Continuation = Objects.requireNonNull(moc3Continuation, "moc3Continuation");
        moc3Chooser = Objects.requireNonNull(moc3Chooser, "moc3Chooser");
        gatedContinuation = Objects.requireNonNull(gatedContinuation, "gatedContinuation");
        gatedChooser = Objects.requireNonNull(gatedChooser, "gatedChooser");
    }

    /** @return every pinned selector of this profile in installer order */
    public List<StaticSelector> selectors() {
        return List.of(exporterOwner, moc3Continuation, moc3Chooser, gatedContinuation, gatedChooser);
    }

    /**
     * Resolves the reviewed chooser-redirect selectors for a host artifact.
     *
     * <p>Only the exact reviewed builds carrying a protected-export record are recognised. Any other
     * artifact yields empty, so an unreviewed or unexpectedly replaced host never receives the
     * transformer.</p>
     */
    public static Optional<ProtectedExportChooserProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return Optional.ofNullable(BY_ARTIFACT.get(artifact));
    }

    /**
     * Resolves the reviewed chooser-redirect selectors for a reviewed Cubism version.
     *
     * @param hostVersion exact reviewed Cubism version string
     * @return the matching profile, or empty when unsupported
     * @throws NullPointerException if {@code hostVersion} is {@code null}
     */
    public static Optional<ProtectedExportChooserProfile> forReviewedVersion(final String hostVersion) {
        Objects.requireNonNull(hostVersion, "hostVersion");
        final String windowPanel;
        if (WINDOW_PANEL_5_2_VERSIONS.contains(hostVersion)) {
            windowPanel = WINDOW_PANEL_5_2;
        } else if (WINDOW_PANEL_5_3_VERSIONS.contains(hostVersion)) {
            windowPanel = WINDOW_PANEL_5_3;
        } else {
            return Optional.empty();
        }
        return Optional.of(reviewedProfile(
                hostVersion, windowPanel, "cubism.mapping.v" + hostVersion.replace(".", "") + ".protected_export."));
    }

    private static Map<HostArtifactDigest, ProtectedExportChooserProfile> buildProfilesByArtifact() {
        final Map<HostArtifactDigest, ProtectedExportChooserProfile> profiles = new LinkedHashMap<>();
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            final String version = ReviewedHostArtifacts.cubismVersionOf(artifact)
                    .orElseThrow(() -> new IllegalStateException("Missing Cubism version for reviewed artifact"));
            forReviewedVersion(version).ifPresent(profile -> profiles.put(artifact, profile));
        }
        return Map.copyOf(profiles);
    }

    private static String descriptor(final String internalName) {
        return "L" + internalName + ";";
    }
}
