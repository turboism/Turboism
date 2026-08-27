package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime trust root for the reviewed native bottom status bar.
 *
 * <p>Each reviewed Cubism version is declared as its own {@link ReviewedSliceRecord}. Every other
 * artifact fails closed; no record stands in for a version it was not reviewed against.</p>
 */
public final class StatusBarVerificationManifest {

    private static final String VALIDATION_PROPERTY =
        "turboism.validation.statusBar";
    private static final String VALIDATION_TOKEN =
        "EXACT_5303_STATUS_BAR_CANDIDATE";
    private static final String VALIDATION_HOST_VERSION_PROPERTY =
        "turboism.validation.hostVersion";
    private static final String VALIDATION_HOST_VERSION = "5303";
    private static final String VALIDATION_MODE_PROPERTY =
        "turboism.validation.statusBar.mode";
    private static final String VALIDATION_MODE = "manager";
    private static final String VALIDATION_RUN_ID_PROPERTY =
        "turboism.validation.runId";

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed status-bar record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "cubism-5.2.03.ui-status-bar.static",
        "452e9376e407b608117972819ebabb6a93729534699e8c2f8b82271ca7f99e39",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed status-bar record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "cubism-5.3.02.ui-status-bar.static",
        "8ba8977755edef54e921fc300f0bbe4ba9975c2c8c1b3b6c61afc0e7da4c7f85",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed status-bar record admitted for exact Cubism 5.3.03 static resolution. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "cubism-5.3.03.ui-status-bar.static",
        "7ac88d2e842e85636a2bb3aabc137fa2fd2312a76f442c9de24d6ba48ac54ec7",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_2_03,
        RECORD_5_3_02,
        RECORD_5_3_03
    );

    public static final String ADAPTER_SLICE_ID = "adapter.editor-ui.status-bar";
    public static final String CAPABILITY_ID = "ui.status.notify";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID);

    /** Reviewed exact Cubism versions this status-bar trust root can serve. */
    public static Set<String> reviewedCubismVersions() {
        return Set.of(
            CUBISM_VERSION_5_2_03,
            CUBISM_VERSION_5_3_02,
            CUBISM_VERSION_5_3_03
        );
    }

    /**
     * Returns whether this JVM is the exact task-scoped 5.3.03 status-bar validation lane.
     *
     * <p>The static record and resolver stay available independently. This predicate now identifies
     * only the exact task-scoped validation lane; ordinary 5.3.03 launches use established full-runtime
     * admission and do not depend on this token.</p>
     */
    public static boolean admits5303ValidationCandidate() {
        return admits5303ValidationCandidate(
            System.getProperty(VALIDATION_PROPERTY),
            System.getProperty(VALIDATION_HOST_VERSION_PROPERTY),
            System.getProperty(VALIDATION_MODE_PROPERTY),
            System.getProperty(VALIDATION_RUN_ID_PROPERTY)
        );
    }

    static boolean admits5303ValidationCandidate(
        final String token,
        final String hostVersion,
        final String mode,
        final String runId
    ) {
        return VALIDATION_TOKEN.equals(token)
            && VALIDATION_HOST_VERSION.equals(hostVersion)
            && VALIDATION_MODE.equals(mode)
            && runId != null
            && !runId.isBlank();
    }

    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.ui-status-bar.app-controller.class",
        "cubism.ui-status-bar.app-controller.instance",
        "cubism.ui-status-bar.app-controller.main-frame",
        "cubism.ui-status-bar.main-frame-controller.class",
        "cubism.ui-status-bar.main-frame-controller.frame",
        "cubism.ui-status-bar.frame.class",
        "cubism.ui-status-bar.frame.content-pane",
        "cubism.ui-status-bar.widget.class",
        "cubism.ui-status-bar.widget.set-name",
        "cubism.ui-status-bar.widget.set-tooltip",
        "cubism.ui-status-bar.widget.revalidate",
        "cubism.ui-status-bar.widget.repaint",
        "cubism.ui-status-bar.container.class",
        "cubism.ui-status-bar.container.children",
        "cubism.ui-status-bar.container.add",
        "cubism.ui-status-bar.container.remove",
        "cubism.ui-status-bar.label.class",
        "cubism.ui-status-bar.label.create",
        "cubism.ui-status-bar.label.text",
        "cubism.ui-status-bar.label.set-text",
        "cubism.ui-status-bar.memory-viewer.class"
    );

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(final HostArtifactDigest artifact) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "status-bar")
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
     * Reviewed status-bar admission evidence for one exact artifact.
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

    private StatusBarVerificationManifest() {
    }
}
