package dev.turboism.exportsettings;

import dev.turboism.adapter.cubism.profile.CubismExportHostProfile;
import dev.turboism.compatibility.policy.ReviewedReleaseTable;
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
 * Exact reviewed Embedded-Model Export Settings dialog selectors for supported Cubism artifacts.
 *
 * <p>The seven members below pin {@code com/live2d/cubism/doc/model/exporter/e} and its window
 * base {@code com/live2d/ui/window/y}. The installer refuses any host whose dialog mismatches its
 * exact tuple, and the transformer fails closed on unexpected method cardinality.</p>
 *
 * <p>Reviewed builds from 5.1.00 through 5.2.03 take the abstract window panel
 * {@code com/live2d/ui/window/X}, while builds from 5.3.00 onward take
 * {@code com/live2d/ui/window/V}. Earlier or unreviewed artifacts yield empty.</p>
 */
public record ExportSettingsHostProfile(
        String hostVersion,
        StaticSelector dialogOwner,
        StaticSelector dialogConstructor,
        StaticSelector dialogShow,
        StaticSelector dialogContentBuilder,
        StaticSelector dialogWindowField,
        StaticSelector windowClass,
        StaticSelector windowJDialog) {

    /** Alias of the dialog owner class selector. */
    public static final String DIALOG_OWNER_ALIAS = "cubism.export-settings.dialog.owner";
    /** Alias of the dialog constructor selector. */
    public static final String DIALOG_CONSTRUCTOR_ALIAS = "cubism.export-settings.dialog.constructor";
    /** Alias of the post-modal decision gate selector. */
    public static final String DIALOG_SHOW_ALIAS = "cubism.export-settings.dialog.show";
    /** Alias of the single-{@code RETURN} content builder selector. */
    public static final String DIALOG_CONTENT_BUILDER_ALIAS = "cubism.export-settings.dialog.content-builder";
    /** Alias of the dialog window field selector. */
    public static final String DIALOG_WINDOW_FIELD_ALIAS = "cubism.export-settings.dialog.window-field";
    /** Alias of the window base class selector. */
    public static final String WINDOW_CLASS_ALIAS = "cubism.export-settings.window.class";
    /** Alias of the window's {@code JDialog} accessor selector. */
    public static final String WINDOW_JDIALOG_ALIAS = "cubism.export-settings.window.jdialog";

    private static final int ACCESS_PUBLIC = StaticSelector.ACCESS_PUBLIC;
    private static final int FORBID_STATIC = StaticSelector.ACCESS_STATIC;

    private static final String DIALOG_OWNER = CubismExportHostProfile.SETTINGS_DIALOG_OWNER;
    private static final String DIALOG_SETTING_DATA = CubismExportHostProfile.SETTINGS_DIALOG_SETTING_DATA;
    private static final String MODEL_SOURCE = CubismExportHostProfile.SETTINGS_MODEL_SOURCE;
    private static final String WINDOW_PANEL_5_2 = CubismExportHostProfile.SETTINGS_WINDOW_PANEL_5_2;
    private static final String WINDOW_PANEL_5_3 = CubismExportHostProfile.SETTINGS_WINDOW_PANEL_5_3;
    private static final String WINDOW_BASE = CubismExportHostProfile.SETTINGS_WINDOW_BASE;

    private static final Set<String> WINDOW_PANEL_5_2_VERSIONS = ReviewedVersions.between("5.1.00", "5.2.03");
    private static final Set<String> WINDOW_PANEL_5_3_VERSIONS = ReviewedVersions.since("5.3.00");
    private static final Map<HostArtifactDigest, ExportSettingsHostProfile> BY_ARTIFACT = buildProfilesByArtifact();

    private static ExportSettingsHostProfile reviewedProfile(
            final String hostVersion, final String windowPanel, final String mappingIdPrefix) {
        return new ExportSettingsHostProfile(
                hostVersion,
                new StaticSelector(
                        mappingIdPrefix + "dialog_owner",
                        DIALOG_OWNER_ALIAS,
                        StaticSelector.Kind.CLASS,
                        DIALOG_OWNER,
                        "",
                        "",
                        ACCESS_PUBLIC,
                        0),
                new StaticSelector(
                        mappingIdPrefix + "dialog_constructor",
                        DIALOG_CONSTRUCTOR_ALIAS,
                        StaticSelector.Kind.CONSTRUCTOR,
                        DIALOG_OWNER,
                        "<init>",
                        "(" + descriptor(MODEL_SOURCE) + ")V",
                        ACCESS_PUBLIC,
                        FORBID_STATIC),
                StaticSelector.method(
                        mappingIdPrefix + "dialog_show",
                        DIALOG_SHOW_ALIAS,
                        DIALOG_OWNER,
                        "a",
                        "(" + descriptor(windowPanel) + descriptor(DIALOG_SETTING_DATA)
                                + descriptor(DIALOG_SETTING_DATA) + ")Z",
                        ACCESS_PUBLIC),
                StaticSelector.method(
                        mappingIdPrefix + "dialog_content_builder",
                        DIALOG_CONTENT_BUILDER_ALIAS,
                        DIALOG_OWNER,
                        "b",
                        "()V",
                        0),
                new StaticSelector(
                        mappingIdPrefix + "dialog_window_field",
                        DIALOG_WINDOW_FIELD_ALIAS,
                        StaticSelector.Kind.FIELD,
                        DIALOG_OWNER,
                        "c",
                        descriptor(WINDOW_BASE),
                        0,
                        FORBID_STATIC),
                new StaticSelector(
                        mappingIdPrefix + "window_class",
                        WINDOW_CLASS_ALIAS,
                        StaticSelector.Kind.CLASS,
                        WINDOW_BASE,
                        "",
                        "",
                        ACCESS_PUBLIC,
                        0),
                StaticSelector.method(
                        mappingIdPrefix + "window_jdialog",
                        WINDOW_JDIALOG_ALIAS,
                        WINDOW_BASE,
                        "e",
                        "()Ljavax/swing/JDialog;",
                        ACCESS_PUBLIC));
    }

    public ExportSettingsHostProfile {
        hostVersion = Requirements.text(hostVersion, "hostVersion");
        dialogOwner = Objects.requireNonNull(dialogOwner, "dialogOwner");
        dialogConstructor = Objects.requireNonNull(dialogConstructor, "dialogConstructor");
        dialogShow = Objects.requireNonNull(dialogShow, "dialogShow");
        dialogContentBuilder = Objects.requireNonNull(dialogContentBuilder, "dialogContentBuilder");
        dialogWindowField = Objects.requireNonNull(dialogWindowField, "dialogWindowField");
        windowClass = Objects.requireNonNull(windowClass, "windowClass");
        windowJDialog = Objects.requireNonNull(windowJDialog, "windowJDialog");
    }

    /** @return every pinned selector of this profile in installer order */
    public List<StaticSelector> selectors() {
        return List.of(
                dialogOwner,
                dialogConstructor,
                dialogShow,
                dialogContentBuilder,
                dialogWindowField,
                windowClass,
                windowJDialog);
    }

    /** @return the host versions whose reviewed artifacts carry an export-settings profile */
    public static List<String> supportedHostVersions() {
        final Set<String> supported = ReviewedVersions.since("5.1.00");
        return ReviewedReleaseTable.versions().stream()
                .filter(supported::contains)
                .toList();
    }

    /**
     * Resolves the reviewed export-settings selectors for a host artifact.
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed supported build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<ExportSettingsHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return Optional.ofNullable(BY_ARTIFACT.get(artifact));
    }

    /**
     * Resolves the reviewed export-settings selectors for a reviewed Cubism version.
     *
     * @param hostVersion exact reviewed Cubism version string
     * @return the matching profile, or empty when unsupported
     * @throws NullPointerException if {@code hostVersion} is {@code null}
     */
    public static Optional<ExportSettingsHostProfile> forReviewedVersion(final String hostVersion) {
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
                hostVersion, windowPanel, "cubism.mapping.v" + hostVersion.replace('.', '_') + ".export_settings."));
    }

    private static Map<HostArtifactDigest, ExportSettingsHostProfile> buildProfilesByArtifact() {
        final Map<HostArtifactDigest, ExportSettingsHostProfile> profiles = new LinkedHashMap<>();
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
