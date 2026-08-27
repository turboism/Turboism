package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime trust root for exact-version Cubism native main-toolbar providers.
 *
 * <p>Each reviewed Cubism version is declared as its own {@link ReviewedSliceRecord}; every
 * other artifact fails closed.</p>
 */
public final class MainToolbarVerificationManifest {

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed main-toolbar record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "cubism-5.2.03.ui-main-toolbar.static",
        "b8e6de878db814fa58fcdceb8213fe9f147a9a4bebdf54a9033ca211ff8dd7d8",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed main-toolbar record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "cubism-5.3.02.ui-main-toolbar.static",
        "fa95beaca4ba59509f59d817e6411629f82aec1a2b0e7f8b8ec4dc36846cf9a5",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed main-toolbar record admitted for exact Cubism 5.3.03 static resolution. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "cubism-5.3.03.ui-main-toolbar.static",
        "06a8e47b238503f224b60f6c3e29e404c2c7ca3041f1e3418f855badeb542262",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_2_03,
        RECORD_5_3_02,
        RECORD_5_3_03
    );

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.main-toolbar";
    public static final String CAPABILITY_ID = "cubism.editor-ui.main-toolbar";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-main-toolbar.app-controller.class",
        "cubism.ui-main-toolbar.app-controller.instance",
        "cubism.ui-main-toolbar.app-controller.main-frame",
        "cubism.ui-main-toolbar.main-frame.class",
        "cubism.ui-main-toolbar.main-frame.view",
        "cubism.ui-main-toolbar.main-frame-view.class",
        "cubism.ui-main-toolbar.main-frame-view.home-button",
        "cubism.ui-main-toolbar.main-frame-view.main-container",
        "cubism.ui-main-toolbar.vbox.create",
        "cubism.ui-main-toolbar.widget.class",
        "cubism.ui-main-toolbar.widget.jcomponent",
        "cubism.ui-main-toolbar.widget.parent",
        "cubism.ui-main-toolbar.widget.name",
        "cubism.ui-main-toolbar.widget.set-name",
        "cubism.ui-main-toolbar.widget.set-tooltip",
        "cubism.ui-main-toolbar.widget.set-pref-width",
        "cubism.ui-main-toolbar.widget.set-pref-height",
        "cubism.ui-main-toolbar.widget.revalidate",
        "cubism.ui-main-toolbar.widget.repaint",
        "cubism.ui-main-toolbar.container.class",
        "cubism.ui-main-toolbar.container.children",
        "cubism.ui-main-toolbar.container.add",
        "cubism.ui-main-toolbar.container.remove",
        "cubism.ui-main-toolbar.icon-button.class",
        "cubism.ui-main-toolbar.icon-button.create",
        "cubism.ui-main-toolbar.icon-button.set-rollover-icon",
        "cubism.ui-main-toolbar.icon.class",
        "cubism.ui-main-toolbar.icon.create"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "main-toolbar")
            .toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES);
    }

    /**
     * Returns the reviewed admission evidence for an artifact.
     *
     * @param artifact the observed host artifact identity
     * @return evidence carrying the reviewed version, artifact binding, slice and record digest
     * @throws IllegalArgumentException when the artifact is not reviewed for this family
     */
    public static AdmissionEvidence admissionForArtifact(
        final HostArtifactDigest artifact
    ) {
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
     * Reviewed main-toolbar admission evidence for one exact artifact.
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

    private MainToolbarVerificationManifest() {
    }
}
