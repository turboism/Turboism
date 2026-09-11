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
 * <p>The seven members below are frozen from the reviewed exact Cubism 5.3.02 classfile
 * observation of {@code com/live2d/cubism/doc/model/exporter/e} and its window base
 * {@code com/live2d/ui/window/y}. They pin the same dialog shape the transformer and installer
 * enforce, so a host whose dialog does not match this exact tuple is refused instead of being
 * transformed. The in-host transformer additionally fails closed on any unexpected method
 * cardinality, so an unrecognised build is left byte-identical.</p>
 *
 * <p>Only the exact reviewed 5.3.02 build is recognised; every other artifact yields empty, which
 * is the fail-closed signal that the export-settings hook must not be installed.</p>
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
    private static final String WINDOW_PANEL = "com/live2d/ui/window/V";
    private static final String WINDOW_BASE = "com/live2d/ui/window/y";

    private static final String MAPPING_ID_PREFIX = "cubism.mapping.v5_3_02.export_settings.";

    /** The exact reviewed 5.3.02 tuple. */
    public static final ExportSettingsHostProfile CUBISM_5_3_02 = new ExportSettingsHostProfile(
        "5.3.02",
        new StaticSelector(
            MAPPING_ID_PREFIX + "dialog_owner", DIALOG_OWNER_ALIAS, StaticSelector.Kind.CLASS,
            DIALOG_OWNER, "", "", ACCESS_PUBLIC, 0
        ),
        new StaticSelector(
            MAPPING_ID_PREFIX + "dialog_constructor", DIALOG_CONSTRUCTOR_ALIAS,
            StaticSelector.Kind.CONSTRUCTOR, DIALOG_OWNER, "<init>",
            "(" + descriptor(MODEL_SOURCE) + ")V", ACCESS_PUBLIC, FORBID_STATIC
        ),
        StaticSelector.method(
            MAPPING_ID_PREFIX + "dialog_show", DIALOG_SHOW_ALIAS, DIALOG_OWNER, "a",
            "(" + descriptor(WINDOW_PANEL) + descriptor(DIALOG_SETTING_DATA)
                + descriptor(DIALOG_SETTING_DATA) + ")Z",
            ACCESS_PUBLIC
        ),
        StaticSelector.method(
            MAPPING_ID_PREFIX + "dialog_content_builder", DIALOG_CONTENT_BUILDER_ALIAS,
            DIALOG_OWNER, "b", "()V", 0
        ),
        new StaticSelector(
            MAPPING_ID_PREFIX + "dialog_window_field", DIALOG_WINDOW_FIELD_ALIAS,
            StaticSelector.Kind.FIELD, DIALOG_OWNER, "c", descriptor(WINDOW_BASE), 0,
            FORBID_STATIC
        ),
        new StaticSelector(
            MAPPING_ID_PREFIX + "window_class", WINDOW_CLASS_ALIAS, StaticSelector.Kind.CLASS,
            WINDOW_BASE, "", "", ACCESS_PUBLIC, 0
        ),
        StaticSelector.method(
            MAPPING_ID_PREFIX + "window_jdialog", WINDOW_JDIALOG_ALIAS, WINDOW_BASE, "e",
            "()Ljavax/swing/JDialog;", ACCESS_PUBLIC
        )
    );

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

    /**
     * Resolves the reviewed export-settings selectors for a host artifact.
     *
     * <p>Only exact Cubism 5.3.02 is recognised. Any other artifact yields empty, so an unreviewed
     * or unexpectedly replaced host never receives the transformer.</p>
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not the reviewed 5.3.02 build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<ExportSettingsHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)
            ? Optional.of(CUBISM_5_3_02)
            : Optional.empty();
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
