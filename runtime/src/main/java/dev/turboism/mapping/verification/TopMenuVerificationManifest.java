package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime trust root for exact-version Cubism top-menu providers.
 *
 * <p>Both supported Cubism versions are declared symmetrically as {@link ReviewedSliceRecord}
 * data; every other artifact fails closed.</p>
 */
public final class TopMenuVerificationManifest {

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Reviewed top-menu record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "cubism-5.2.03.ui-top-menu.static",
        "05bbafb1504b809842f21419a6eda08dc5cf96ed022ee7d3bfdd7adb633c5fb9",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed top-menu record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "cubism-5.3.02.ui-top-menu.static",
        "fa98853b6b834a6937f27c8b48119d3e56cfabbf96305aa57ad3427c7393850e",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(RECORD_5_2_03, RECORD_5_3_02);

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.top-menu";
    public static final String CAPABILITY_ID = "cubism.editor-ui.top-menu";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-top-menu.app-controller.instance",
        "cubism.ui-top-menu.app-controller.main-frame",
        "cubism.ui-top-menu.main-frame.window",
        "cubism.ui-top-menu.menu.add",
        "cubism.ui-top-menu.menu-bar.add",
        "cubism.ui-top-menu.menu-bar.menus",
        "cubism.ui-top-menu.menu-bar.swing",
        "cubism.ui-top-menu.menu.create",
        "cubism.ui-top-menu.menu-item.create",
        "cubism.ui-top-menu.menu.swing",
        "cubism.editor-command.canvas.begin-edit",
        "cubism.editor-command.canvas.canvas",
        "cubism.editor-command.canvas.companion",
        "cubism.editor-command.canvas.complete-pack",
        "cubism.editor-command.canvas.current-view-context",
        "cubism.editor-command.canvas.doc-size",
        "cubism.editor-command.canvas.edit-mode",
        "cubism.editor-command.canvas.edit-mode-main",
        "cubism.editor-command.canvas.end-edit-default",
        "cubism.editor-command.canvas.group-add",
        "cubism.editor-command.canvas.handler",
        "cubism.editor-command.canvas.is-editing",
        "cubism.editor-command.canvas.mark-dirty",
        "cubism.editor-command.canvas.model",
        "cubism.editor-command.canvas.model-source",
        "cubism.editor-command.canvas.modeling-doc",
        "cubism.editor-command.canvas.modeling-view",
        "cubism.editor-command.canvas.notify-size",
        "cubism.editor-command.canvas.pixel-height",
        "cubism.editor-command.canvas.pixel-width",
        "cubism.editor-command.canvas.scale-with-anchor",
        "cubism.editor-command.canvas.set-pixel-height",
        "cubism.editor-command.canvas.set-pixel-width",
        "cubism.editor-command.canvas.simple-undo",
        "cubism.editor-command.canvas.size-height",
        "cubism.editor-command.canvas.size-width",
        "cubism.editor-command.canvas.undo",
        "cubism.editor-command.canvas.undo-manager",
        "cubism.editor-command.canvas.undo-pos",
        "cubism.editor-command.canvas.vector2",
        "cubism.editor-command.canvas.vector2-zero",
        "cubism.editor-command.config.instance",
        "cubism.editor-command.config.read",
        "cubism.editor-command.config.write",
        "cubism.editor-command.external-app.companion",
        "cubism.editor-command.external-app.connected",
        "cubism.editor-command.external-app.get-port",
        "cubism.editor-command.external-app.get-remote",
        "cubism.editor-command.external-app.instance",
        "cubism.editor-command.external-app.manager",
        "cubism.editor-command.external-app.set-port",
        "cubism.editor-command.external-app.set-remote",
        "cubism.editor-command.external-app.start",
        "cubism.editor-command.external-app.stop",
        "cubism.editor-command.file.save-model",
        "cubism.editor-command.file.save-scene",
        "cubism.editor-command.file.scene-content",
        "cubism.editor-command.file.scene-document",
        "cubism.editor-command.grid.all-view-contexts",
        "cubism.editor-command.grid.color-create",
        "cubism.editor-command.grid.developer-setting",
        "cubism.editor-command.grid.entity",
        "cubism.editor-command.grid.entity-from-draw",
        "cubism.editor-command.grid.get-bold",
        "cubism.editor-command.grid.get-color",
        "cubism.editor-command.grid.get-jcolor",
        "cubism.editor-command.grid.get-spacing",
        "cubism.editor-command.grid.modeling-draw",
        "cubism.editor-command.grid.repaint-default",
        "cubism.editor-command.grid.set-color",
        "cubism.editor-command.grid.set-reset",
        "cubism.editor-command.grid.set-spacing",
        "cubism.editor-command.grid.update-manager",
        "cubism.editor-command.resize.guard",
        "cubism.editor-command.resize.guard-active",
        "cubism.editor-command.resize.guard-current",
        "cubism.editor-command.resize.scale-model",
        "cubism.ui-top-menu.widget.name",
        "cubism.ui-top-menu.widget.repaint",
        "cubism.ui-top-menu.widget.revalidate",
        "cubism.ui-top-menu.widget.set-name",
        "cubism.ui-top-menu.window.menu-bar"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "top-menu")
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
     * Reviewed top-menu admission evidence for one exact artifact.
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

    private TopMenuVerificationManifest() {
    }
}
