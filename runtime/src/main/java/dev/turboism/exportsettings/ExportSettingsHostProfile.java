package dev.turboism.exportsettings;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact reviewed Embedded-Model Export Settings dialog selectors for supported Cubism artifacts.
 *
 * <p>The seven members below are frozen from reviewed exact classfile observation of
 * {@code com/live2d/cubism/doc/model/exporter/e} and its window base
 * {@code com/live2d/ui/window/y}. They pin the same dialog shape the transformer and installer
 * enforce, so a host whose dialog does not match its exact tuple is refused instead of being
 * transformed. The in-host transformer additionally fails closed on any unexpected method
 * cardinality, so an unrecognised build is left byte-identical.</p>
 *
 * <p>Cubism 5.3.02 and 5.3.03 share one selector tuple whose dialog methods take the abstract
 * window panel {@code com/live2d/ui/window/V}. On the reviewed 5.2.03 build the same abstract
 * panel is named {@code com/live2d/ui/window/X} (the 5.2 window base {@code y} extends it
 * directly), so 5.2.03 carries its own tuple rather than reusing the 5.3 descriptors. Every
 * other artifact yields empty, which is the fail-closed signal that the export-settings hook
 * must not be installed.</p>
 */
public record ExportSettingsHostProfile(
    String hostVersion,
    StaticSelector dialogOwner,
    StaticSelector dialogConstructor,
    StaticSelector dialogShow,
    StaticSelector dialogContentBuilder,
    StaticSelector dialogWindowField,
    StaticSelector windowClass,
    StaticSelector windowJDialog
) {

    /** Alias of the dialog owner class selector. */
    public static final String DIALOG_OWNER_ALIAS = "cubism.export-settings.dialog.owner";
    /** Alias of the dialog constructor selector. */
    public static final String DIALOG_CONSTRUCTOR_ALIAS = "cubism.export-settings.dialog.constructor";
    /** Alias of the post-modal decision gate selector. */
    public static final String DIALOG_SHOW_ALIAS = "cubism.export-settings.dialog.show";
    /** Alias of the single-{@code RETURN} content builder selector. */
    public static final String DIALOG_CONTENT_BUILDER_ALIAS =
        "cubism.export-settings.dialog.content-builder";
    /** Alias of the dialog window field selector. */
    public static final String DIALOG_WINDOW_FIELD_ALIAS =
        "cubism.export-settings.dialog.window-field";
    /** Alias of the window base class selector. */
    public static final String WINDOW_CLASS_ALIAS = "cubism.export-settings.window.class";
    /** Alias of the window's {@code JDialog} accessor selector. */
    public static final String WINDOW_JDIALOG_ALIAS = "cubism.export-settings.window.jdialog";

    private static final int ACCESS_PUBLIC = StaticSelector.ACCESS_PUBLIC;
    private static final int FORBID_STATIC = StaticSelector.ACCESS_STATIC;

    private static final String DIALOG_OWNER = "com/live2d/cubism/doc/model/exporter/e";
    private static final String DIALOG_SETTING_DATA =
        "com/live2d/cubism/doc/model/exporter/CModelExportSettingDialogData";
    private static final String MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";
    private static final String WINDOW_PANEL_5_2 = "com/live2d/ui/window/X";
    private static final String WINDOW_PANEL_5_3 = "com/live2d/ui/window/V";
    private static final String WINDOW_BASE = "com/live2d/ui/window/y";

    /** The exact reviewed 5.2.03 tuple: identical shape with the {@code X} window panel. */
    public static final ExportSettingsHostProfile CUBISM_5_2_03 = reviewedProfile(
        "5.2.03", WINDOW_PANEL_5_2, "cubism.mapping.v5_2_03.export_settings."
    );

    /** The exact reviewed 5.3.02 tuple. */
    public static final ExportSettingsHostProfile CUBISM_5_3_02 = reviewedProfile(
        "5.3.02", WINDOW_PANEL_5_3, "cubism.mapping.v5_3_02.export_settings."
    );

    /** The exact reviewed 5.3.03 tuple: identical shape to 5.3.02 on its own reviewed build. */
    public static final ExportSettingsHostProfile CUBISM_5_3_03 = reviewedProfile(
        "5.3.03", WINDOW_PANEL_5_3, "cubism.mapping.v5_3_03.export_settings."
    );

    private static ExportSettingsHostProfile reviewedProfile(
        final String hostVersion,
        final String windowPanel,
        final String mappingIdPrefix
    ) {
        return new ExportSettingsHostProfile(
            hostVersion,
            new StaticSelector(
                mappingIdPrefix + "dialog_owner", DIALOG_OWNER_ALIAS, StaticSelector.Kind.CLASS,
                DIALOG_OWNER, "", "", ACCESS_PUBLIC, 0
            ),
            new StaticSelector(
                mappingIdPrefix + "dialog_constructor", DIALOG_CONSTRUCTOR_ALIAS,
                StaticSelector.Kind.CONSTRUCTOR, DIALOG_OWNER, "<init>",
                "(" + descriptor(MODEL_SOURCE) + ")V", ACCESS_PUBLIC, FORBID_STATIC
            ),
            StaticSelector.method(
                mappingIdPrefix + "dialog_show", DIALOG_SHOW_ALIAS, DIALOG_OWNER, "a",
                "(" + descriptor(windowPanel) + descriptor(DIALOG_SETTING_DATA)
                    + descriptor(DIALOG_SETTING_DATA) + ")Z",
                ACCESS_PUBLIC
            ),
            StaticSelector.method(
                mappingIdPrefix + "dialog_content_builder", DIALOG_CONTENT_BUILDER_ALIAS,
                DIALOG_OWNER, "b", "()V", 0
            ),
            new StaticSelector(
                mappingIdPrefix + "dialog_window_field", DIALOG_WINDOW_FIELD_ALIAS,
                StaticSelector.Kind.FIELD, DIALOG_OWNER, "c", descriptor(WINDOW_BASE), 0,
                FORBID_STATIC
            ),
            new StaticSelector(
                mappingIdPrefix + "window_class", WINDOW_CLASS_ALIAS, StaticSelector.Kind.CLASS,
                WINDOW_BASE, "", "", ACCESS_PUBLIC, 0
            ),
            StaticSelector.method(
                mappingIdPrefix + "window_jdialog", WINDOW_JDIALOG_ALIAS, WINDOW_BASE, "e",
                "()Ljavax/swing/JDialog;", ACCESS_PUBLIC
            )
        );
    }

    public ExportSettingsHostProfile {
        hostVersion = requireText(hostVersion, "hostVersion");
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
            windowJDialog
        );
    }

    /** @return the host versions whose reviewed artifacts carry an export-settings profile */
    public static List<String> supportedHostVersions() {
        return List.of(
            CUBISM_5_2_03.hostVersion(),
            CUBISM_5_3_02.hostVersion(),
            CUBISM_5_3_03.hostVersion()
        );
    }

    /**
     * Resolves the reviewed export-settings selectors for a host artifact.
     *
     * <p>Only the exact reviewed 5.2.03, 5.3.02 and 5.3.03 builds are recognised. Any other
     * artifact yields empty, so an unreviewed or unexpectedly replaced host never receives the
     * transformer.</p>
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed supported build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<ExportSettingsHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return Optional.of(CUBISM_5_2_03);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return Optional.of(CUBISM_5_3_02);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return Optional.of(CUBISM_5_3_03);
        }
        return Optional.empty();
    }

    private static String descriptor(final String internalName) {
        return "L" + internalName + ";";
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
