package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime trust root for exact-version Cubism embedded-panel providers.
 *
 * <p>Each reviewed Cubism version is declared as its own {@link ReviewedSliceRecord}; every
 * other artifact fails closed.</p>
 */
public final class EmbeddedPanelVerificationManifest {

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed embedded-panel record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "cubism-5.2.03.ui-embedded-panel.static",
        "5ec9331ab80b79f6eff6777f282738bfbe26400620b2e27e23715963a23b7d89",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed embedded-panel record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "cubism-5.3.02.ui-embedded-panel.static",
        "6f8514a907b77b93f1eef36f2e4226455a6eceec820efae736868ee81ee45a2a",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed embedded-panel record admitted for exact Cubism 5.3.03 static resolution. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "cubism-5.3.03.ui-embedded-panel.static",
        "f520ec5496dfd80b78c0dad54d6f9ab0db56142720156b4c551cd38c02cfdb23",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_2_03,
        RECORD_5_3_02,
        RECORD_5_3_03
    );

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.embedded-panel";
    public static final String CAPABILITY_ID = "cubism.editor-ui.embedded-panel";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-panel.app-controller.class",
        "cubism.ui-panel.app-controller.instance",
        "cubism.ui-panel.app-controller.main-frame",
        "cubism.ui-panel.app-controller.repaint",
        "cubism.ui-panel.main-frame.class",
        "cubism.ui-panel.main-frame.dock-manager",
        "cubism.ui-panel.dock.class",
        "cubism.ui-panel.dock.palette-manager",
        "cubism.ui-panel.dock.set-palette-visible",
        "cubism.ui-panel.dock.update-window-menu",
        "cubism.ui-panel.palette-manager.class",
        "cubism.ui-panel.palette-manager.get",
        "cubism.ui-panel.palette-manager.add",
        "cubism.ui-panel.palette-manager.close",
        "cubism.ui-panel.palette-manager.current-workspace",
        "cubism.ui-panel.workspace.class",
        "cubism.ui-panel.workspace.activate",
        "cubism.ui-panel.workspace.palette-box-for",
        "cubism.ui-panel.palette-box.class",
        "cubism.ui-panel.palette-box.remove-tab",
        "cubism.ui-panel.palette-manager.remove-update",
        "cubism.ui-panel.palette-manager.main-frame-window",
        "cubism.ui-panel.palette-manager.verify-cleanup",
        "cubism.ui-panel.palette-manager.fire-state",
        "cubism.ui-panel.workspace.add-palette-frame",
        "cubism.ui-panel.workspace.remove-palette-frame",
        "cubism.ui-panel.workspace.first-palette-box",
        "cubism.ui-panel.palette-box.create",
        "cubism.ui-panel.palette-box.add-tab",
        "cubism.ui-panel.palette-box.set-selected",
        "cubism.ui-panel.palette-box.palettes",
        "cubism.ui-panel.palette-box.tab-panel",
        "cubism.ui-panel.tab-panel.entries",
        "cubism.ui-panel.tab-entry.palette",
        "cubism.ui-panel.tab-entry.button",
        "cubism.ui-panel.widget.jcomponent",
        "cubism.ui-panel.palette-frame.create",
        "cubism.ui-panel.palette-frame.root",
        "cubism.ui-panel.palette-frame.window",
        "cubism.ui-panel.palette-frame.dispose",
        "cubism.ui-panel.palette-frame.raw-disposed",
        "cubism.ui-panel.floating-tab-close.operation",
        "cubism.ui-panel.floating-tab-close.palette-field",
        "cubism.ui-panel.workspace.root-container",
        "cubism.ui-panel.root.component",
        "cubism.ui-panel.split.class",
        "cubism.ui-panel.split.contents",
        "cubism.ui-panel.split.remove",
        "cubism.ui-panel.component.palette-count",
        "cubism.ui-panel.root.set-component",
        "cubism.ui-panel.window.set-visible",
        "cubism.ui-panel.palette-id.class",
        "cubism.ui-panel.palette-id.create",
        "cubism.ui-panel.palette.class",
        "cubism.ui-panel.palette.create",
        "cubism.ui-panel.palette.id",
        "cubism.ui-panel.palette.set-panel",
        "cubism.ui-panel.dock-tab-popup.operation",
        "cubism.ui-panel.dock-tab-popup.palette-field",
        "cubism.ui-panel.dock-tab-popup.menu-append",
        "cubism.ui-panel.swing-container.class",
        "cubism.ui-panel.swing-container.create",
        "cubism.ui-panel.main-frame.window",
        "cubism.ui-panel.window.menu-bar",
        "cubism.ui-panel.menu-bar.menus",
        "cubism.ui-panel.widget.name",
        "cubism.ui-panel.widget.set-name",
        "cubism.ui-panel.widget.revalidate",
        "cubism.ui-panel.widget.repaint",
        "cubism.ui-panel.menu.items",
        "cubism.ui-panel.menu.add",
        "cubism.ui-panel.menu.swing",
        "cubism.ui-panel.menu-item.create",
        "cubism.ui-panel.menu-item.check.create",
        "cubism.ui-panel.menu-item.is-selected",
        "cubism.ui-panel.menu-item.swing",
        "cubism.ui-panel.dock.main-frame-ctrl",
        "cubism.ui-panel.main-frame.palette-menu-map"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "embedded-panel")
            .toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES);
    }

    /**
     * Returns the reviewed admission evidence for an artifact.
     *
     * @param artifact the observed host artifact identity
     * @return evidence carrying the reviewed version, artifact binding, slice and record digest
     * @throws IllegalArgumentException when the artifact is not reviewed for this family
     */
    public static AdmissionEvidence admissionForArtifact(final HostArtifactDigest artifact) {
        final PinnedVerifiedResolverWorkflow.Manifest manifest = forArtifact(artifact);
        return new AdmissionEvidence(
            manifest.cubismVersion(),
            manifest.artifactSize(),
            manifest.artifactSha256(),
            manifest.adapterSliceId(),
            manifest.recordSha256()
        );
    }

    /**
     * Reviewed embedded-panel admission evidence for one exact artifact.
     *
     * @param cubismVersion the reviewed Cubism version
     * @param artifactSize the reviewed artifact byte size
     * @param artifactSha256 the reviewed artifact SHA-256
     * @param adapterSliceId the adapter slice identity
     * @param recordSha256 SHA-256 of the reviewed record bytes
     */
    public record AdmissionEvidence(
        String cubismVersion,
        long artifactSize,
        String artifactSha256,
        String adapterSliceId,
        String recordSha256
    ) {
    }

    private EmbeddedPanelVerificationManifest() {
    }
}
