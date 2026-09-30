package dev.turboism.exportsettings;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact reviewed selectors for the embedded-model exporter chooser seam.
 *
 * <p>Frozen from reviewed exact classfile observation of
 * {@code com/live2d/cubism/doc/model/exporter/b}: the private export continuation
 * methods {@code b} and {@code c} each call their own private chooser
 * ({@code a(panel,String)} and {@code a(String,boolean,panel)}) exactly once. A host whose
 * exporter does not match its exact tuple is refused instead of being transformed, and the
 * in-host transformer additionally fails closed on any cardinality drift.</p>
 *
 * <p>Cubism 5.3.02 and 5.3.03 share one selector tuple whose continuations take the abstract
 * window panel {@code com/live2d/ui/window/V}. On the reviewed 5.2.03 build the same abstract
 * panel is named {@code com/live2d/ui/window/X}, so 5.2.03 carries its own tuple. Every other
 * artifact yields empty, which is the fail-closed signal that the redirect hook must not be
 * installed.</p>
 */
public record ProtectedExportChooserProfile(
    String hostVersion,
    StaticSelector exporterOwner,
    StaticSelector moc3Continuation,
    StaticSelector moc3Chooser,
    StaticSelector gatedContinuation,
    StaticSelector gatedChooser
) {

    /** Alias of the exporter owner class selector. */
    public static final String EXPORTER_OWNER_ALIAS = "cubism.protected-export.exporter.owner";
    /** Alias of the moc3 export continuation selector. */
    public static final String MOC3_CONTINUATION_ALIAS =
        "cubism.protected-export.exporter.moc3-continuation";
    /** Alias of the moc3 chooser selector. */
    public static final String MOC3_CHOOSER_ALIAS =
        "cubism.protected-export.exporter.moc3-chooser";
    /** Alias of the save-gated export continuation selector. */
    public static final String GATED_CONTINUATION_ALIAS =
        "cubism.protected-export.exporter.gated-continuation";
    /** Alias of the save-gated chooser selector. */
    public static final String GATED_CHOOSER_ALIAS =
        "cubism.protected-export.exporter.gated-chooser";

    private static final int ACCESS_PRIVATE = 0x0002;
    private static final int FORBID_STATIC = StaticSelector.ACCESS_STATIC;

    private static final String EXPORTER = "com/live2d/cubism/doc/model/exporter/b";
    private static final String WINDOW_PANEL_5_2 = "com/live2d/ui/window/X";
    private static final String WINDOW_PANEL_5_3 = "com/live2d/ui/window/V";
    private static final String MODEL_SOURCE = "com/live2d/cubism/doc/model/CModelSource";
    private static final String FUNCTION2 = "kotlin/jvm/functions/Function2";

    /** The exact reviewed 5.1.00 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_1_00 = reviewedProfile(
        "5.1.00", WINDOW_PANEL_5_2, "cubism.mapping.v5100.protected_export."
    );

    /** The exact reviewed 5.1.01 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_1_01 = reviewedProfile(
        "5.1.01", WINDOW_PANEL_5_2, "cubism.mapping.v5101.protected_export."
    );

    /** The exact reviewed 5.1.02 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_1_02 = reviewedProfile(
        "5.1.02", WINDOW_PANEL_5_2, "cubism.mapping.v5102.protected_export."
    );

    /** The exact reviewed 5.1.03 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_1_03 = reviewedProfile(
        "5.1.03", WINDOW_PANEL_5_2, "cubism.mapping.v5103.protected_export."
    );

    /** The exact reviewed 5.2.00 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_2_00 = reviewedProfile(
        "5.2.00", WINDOW_PANEL_5_2, "cubism.mapping.v5200.protected_export."
    );

    /** The exact reviewed 5.2.01 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_2_01 = reviewedProfile(
        "5.2.01", WINDOW_PANEL_5_2, "cubism.mapping.v5201.protected_export."
    );

    /** The exact reviewed 5.2.02 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_2_02 = reviewedProfile(
        "5.2.02", WINDOW_PANEL_5_2, "cubism.mapping.v5202.protected_export."
    );

    /** The exact reviewed 5.2.03 tuple: the {@code X} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_2_03 = reviewedProfile(
        "5.2.03", WINDOW_PANEL_5_2, "cubism.mapping.v5203.protected_export."
    );

    /** The exact reviewed 5.3.00 tuple: the {@code V} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_3_00 = reviewedProfile(
        "5.3.00", WINDOW_PANEL_5_3, "cubism.mapping.v5300.protected_export."
    );

    /** The exact reviewed 5.3.01 tuple: the {@code V} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_3_01 = reviewedProfile(
        "5.3.01", WINDOW_PANEL_5_3, "cubism.mapping.v5301.protected_export."
    );

    /** The exact reviewed 5.3.02 tuple: the {@code V} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_3_02 = reviewedProfile(
        "5.3.02", WINDOW_PANEL_5_3, "cubism.mapping.v5302.protected_export."
    );

    /** The exact reviewed 5.3.03 tuple: the {@code V} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_3_03 = reviewedProfile(
        "5.3.03", WINDOW_PANEL_5_3, "cubism.mapping.v5303.protected_export."
    );

    /** The exact reviewed 5.3.04 tuple: the {@code V} window panel on its own reviewed build. */
    public static final ProtectedExportChooserProfile CUBISM_5_3_04 = reviewedProfile(
        "5.3.04", WINDOW_PANEL_5_3, "cubism.mapping.v5304.protected_export."
    );

    private static ProtectedExportChooserProfile reviewedProfile(
        final String hostVersion,
        final String windowPanel,
        final String mappingIdPrefix
    ) {
        return new ProtectedExportChooserProfile(
            hostVersion,
            new StaticSelector(
                mappingIdPrefix + "exporter_owner", EXPORTER_OWNER_ALIAS,
                StaticSelector.Kind.CLASS, EXPORTER, "", "", 0, 0
            ),
            new StaticSelector(
                mappingIdPrefix + "exporter_moc3_continuation", MOC3_CONTINUATION_ALIAS,
                StaticSelector.Kind.METHOD, EXPORTER, "b",
                "(" + descriptor(windowPanel) + descriptor(MODEL_SOURCE)
                    + descriptor(FUNCTION2) + ")Z",
                ACCESS_PRIVATE, FORBID_STATIC
            ),
            new StaticSelector(
                mappingIdPrefix + "exporter_moc3_chooser", MOC3_CHOOSER_ALIAS,
                StaticSelector.Kind.METHOD, EXPORTER, "a",
                "(" + descriptor(windowPanel) + "Ljava/lang/String;)Ljava/io/File;",
                ACCESS_PRIVATE, FORBID_STATIC
            ),
            new StaticSelector(
                mappingIdPrefix + "exporter_gated_continuation", GATED_CONTINUATION_ALIAS,
                StaticSelector.Kind.METHOD, EXPORTER, "c",
                "(" + descriptor(windowPanel) + descriptor(MODEL_SOURCE)
                    + descriptor(FUNCTION2) + ")Z",
                ACCESS_PRIVATE, FORBID_STATIC
            ),
            new StaticSelector(
                mappingIdPrefix + "exporter_gated_chooser", GATED_CHOOSER_ALIAS,
                StaticSelector.Kind.METHOD, EXPORTER, "a",
                "(Ljava/lang/String;Z" + descriptor(windowPanel) + ")Ljava/io/File;",
                ACCESS_PRIVATE, FORBID_STATIC
            )
        );
    }

    public ProtectedExportChooserProfile {
        hostVersion = requireText(hostVersion, "hostVersion");
        exporterOwner = Objects.requireNonNull(exporterOwner, "exporterOwner");
        moc3Continuation = Objects.requireNonNull(moc3Continuation, "moc3Continuation");
        moc3Chooser = Objects.requireNonNull(moc3Chooser, "moc3Chooser");
        gatedContinuation = Objects.requireNonNull(gatedContinuation, "gatedContinuation");
        gatedChooser = Objects.requireNonNull(gatedChooser, "gatedChooser");
    }

    /** @return every pinned selector of this profile in installer order */
    public List<StaticSelector> selectors() {
        return List.of(
            exporterOwner,
            moc3Continuation,
            moc3Chooser,
            gatedContinuation,
            gatedChooser
        );
    }

    /**
     * Resolves the reviewed chooser-redirect selectors for a host artifact.
     *
     * <p>Only the exact reviewed builds carrying a protected-export record are recognised. Any other
     * artifact yields empty, so an unreviewed or unexpectedly replaced host never receives the
     * transformer.</p>
     */
    public static Optional<ProtectedExportChooserProfile> forArtifact(
        final HostArtifactDigest artifact
    ) {
        Objects.requireNonNull(artifact, "artifact");
        if (ReviewedHostArtifacts.CUBISM_5_1_00.equals(artifact)) {
            return Optional.of(CUBISM_5_1_00);
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_01.equals(artifact)) {
            return Optional.of(CUBISM_5_1_01);
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_02.equals(artifact)) {
            return Optional.of(CUBISM_5_1_02);
        }
        if (ReviewedHostArtifacts.CUBISM_5_1_03.equals(artifact)) {
            return Optional.of(CUBISM_5_1_03);
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_00.equals(artifact)) {
            return Optional.of(CUBISM_5_2_00);
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_01.equals(artifact)) {
            return Optional.of(CUBISM_5_2_01);
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_02.equals(artifact)) {
            return Optional.of(CUBISM_5_2_02);
        }
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(artifact)) {
            return Optional.of(CUBISM_5_2_03);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_00.equals(artifact)) {
            return Optional.of(CUBISM_5_3_00);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_01.equals(artifact)) {
            return Optional.of(CUBISM_5_3_01);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(artifact)) {
            return Optional.of(CUBISM_5_3_02);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(artifact)) {
            return Optional.of(CUBISM_5_3_03);
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_04.equals(artifact)) {
            return Optional.of(CUBISM_5_3_04);
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
