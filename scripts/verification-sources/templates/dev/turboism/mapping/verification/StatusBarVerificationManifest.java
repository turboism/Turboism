package dev.turboism.mapping.verification;

import dev.turboism.sdk.cubism.ReviewedVersions;
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

    /** Cubism version reported for the reviewed 5.0.00 artifact. */
    public static final String CUBISM_VERSION_5_0_00 = "${record:cubism-5.0.00-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.01 artifact. */
    public static final String CUBISM_VERSION_5_0_01 = "${record:cubism-5.0.01-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.02 artifact. */
    public static final String CUBISM_VERSION_5_0_02 = "${record:cubism-5.0.02-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.03 artifact. */
    public static final String CUBISM_VERSION_5_0_03 = "${record:cubism-5.0.03-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.04 artifact. */
    public static final String CUBISM_VERSION_5_0_04 = "${record:cubism-5.0.04-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.05 artifact. */
    public static final String CUBISM_VERSION_5_0_05 = "${record:cubism-5.0.05-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.06 artifact. */
    public static final String CUBISM_VERSION_5_0_06 = "${record:cubism-5.0.06-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.00 artifact. */
    public static final String CUBISM_VERSION_5_1_00 = "${record:cubism-5.1.00-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.01 artifact. */
    public static final String CUBISM_VERSION_5_1_01 = "${record:cubism-5.1.01-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.02 artifact. */
    public static final String CUBISM_VERSION_5_1_02 = "${record:cubism-5.1.02-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.03 artifact. */
    public static final String CUBISM_VERSION_5_1_03 = "${record:cubism-5.1.03-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.00 artifact. */
    public static final String CUBISM_VERSION_5_2_00 = "${record:cubism-5.2.00-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.01 artifact. */
    public static final String CUBISM_VERSION_5_2_01 = "${record:cubism-5.2.01-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.02 artifact. */
    public static final String CUBISM_VERSION_5_2_02 = "${record:cubism-5.2.02-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "${record:cubism-5.2.03-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.00 artifact. */
    public static final String CUBISM_VERSION_5_3_00 = "${record:cubism-5.3.00-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.01 artifact. */
    public static final String CUBISM_VERSION_5_3_01 = "${record:cubism-5.3.01-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "${record:cubism-5.3.02-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "${record:cubism-5.3.03-ui-status-bar.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.04 artifact. */
    public static final String CUBISM_VERSION_5_3_04 = "${record:cubism-5.3.04-ui-status-bar.json:cubismVersion}";

    /** Reviewed record admitted for exact Cubism 5.0.00. */
    public static final ReviewedSliceRecord RECORD_5_0_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_00,
        "${record:cubism-5.0.00-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.00-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_00,
        "${record:cubism-5.0.00-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.01. */
    public static final ReviewedSliceRecord RECORD_5_0_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_01,
        "${record:cubism-5.0.01-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.01-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_01,
        "${record:cubism-5.0.01-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.02. */
    public static final ReviewedSliceRecord RECORD_5_0_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_02,
        "${record:cubism-5.0.02-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.02-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_02,
        "${record:cubism-5.0.02-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.03. */
    public static final ReviewedSliceRecord RECORD_5_0_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_03,
        "${record:cubism-5.0.03-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.03-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_03,
        "${record:cubism-5.0.03-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.04. */
    public static final ReviewedSliceRecord RECORD_5_0_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_04,
        "${record:cubism-5.0.04-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.04-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_04,
        "${record:cubism-5.0.04-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.05. */
    public static final ReviewedSliceRecord RECORD_5_0_05 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_05,
        "${record:cubism-5.0.05-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.05-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_05,
        "${record:cubism-5.0.05-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.06. */
    public static final ReviewedSliceRecord RECORD_5_0_06 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_06,
        "${record:cubism-5.0.06-ui-status-bar.json:verificationId}",
        "${record:cubism-5.0.06-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_0_06,
        "${record:cubism-5.0.06-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.00. */
    public static final ReviewedSliceRecord RECORD_5_1_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_00,
        "${record:cubism-5.1.00-ui-status-bar.json:verificationId}",
        "${record:cubism-5.1.00-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_1_00,
        "${record:cubism-5.1.00-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.01. */
    public static final ReviewedSliceRecord RECORD_5_1_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_01,
        "${record:cubism-5.1.01-ui-status-bar.json:verificationId}",
        "${record:cubism-5.1.01-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_1_01,
        "${record:cubism-5.1.01-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.02. */
    public static final ReviewedSliceRecord RECORD_5_1_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_02,
        "${record:cubism-5.1.02-ui-status-bar.json:verificationId}",
        "${record:cubism-5.1.02-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_1_02,
        "${record:cubism-5.1.02-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.03. */
    public static final ReviewedSliceRecord RECORD_5_1_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_03,
        "${record:cubism-5.1.03-ui-status-bar.json:verificationId}",
        "${record:cubism-5.1.03-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_1_03,
        "${record:cubism-5.1.03-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.00. */
    public static final ReviewedSliceRecord RECORD_5_2_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_00,
        "${record:cubism-5.2.00-ui-status-bar.json:verificationId}",
        "${record:cubism-5.2.00-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_2_00,
        "${record:cubism-5.2.00-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.01. */
    public static final ReviewedSliceRecord RECORD_5_2_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_01,
        "${record:cubism-5.2.01-ui-status-bar.json:verificationId}",
        "${record:cubism-5.2.01-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_2_01,
        "${record:cubism-5.2.01-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.02. */
    public static final ReviewedSliceRecord RECORD_5_2_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_02,
        "${record:cubism-5.2.02-ui-status-bar.json:verificationId}",
        "${record:cubism-5.2.02-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_2_02,
        "${record:cubism-5.2.02-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "${record:cubism-5.2.03-ui-status-bar.json:verificationId}",
        "${record:cubism-5.2.03-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_2_03,
        "${record:cubism-5.2.03-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.00. */
    public static final ReviewedSliceRecord RECORD_5_3_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_00,
        "${record:cubism-5.3.00-ui-status-bar.json:verificationId}",
        "${record:cubism-5.3.00-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_3_00,
        "${record:cubism-5.3.00-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.01. */
    public static final ReviewedSliceRecord RECORD_5_3_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_01,
        "${record:cubism-5.3.01-ui-status-bar.json:verificationId}",
        "${record:cubism-5.3.01-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_3_01,
        "${record:cubism-5.3.01-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "${record:cubism-5.3.02-ui-status-bar.json:verificationId}",
        "${record:cubism-5.3.02-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_3_02,
        "${record:cubism-5.3.02-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "${record:cubism-5.3.03-ui-status-bar.json:verificationId}",
        "${record:cubism-5.3.03-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_3_03,
        "${record:cubism-5.3.03-ui-status-bar.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.04. */
    public static final ReviewedSliceRecord RECORD_5_3_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_04,
        "${record:cubism-5.3.04-ui-status-bar.json:verificationId}",
        "${record:cubism-5.3.04-ui-status-bar.json:sha256}",
        CUBISM_VERSION_5_3_04,
        "${record:cubism-5.3.04-ui-status-bar.json:profileId}"
    );
    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_0_00,
        RECORD_5_0_01,
        RECORD_5_0_02,
        RECORD_5_0_03,
        RECORD_5_0_04,
        RECORD_5_0_05,
        RECORD_5_0_06,
        RECORD_5_1_00,
        RECORD_5_1_01,
        RECORD_5_1_02,
        RECORD_5_1_03,
        RECORD_5_2_00,
        RECORD_5_2_01,
        RECORD_5_2_02,
        RECORD_5_2_03,
        RECORD_5_3_00,
        RECORD_5_3_01,
        RECORD_5_3_02,
        RECORD_5_3_03,
        RECORD_5_3_04
    );












    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-ui-status-bar.json:adapterSliceId}";
    public static final String CAPABILITY_ID = "ui.status.notify";
    public static final Set<String> CAPABILITY_IDS = Set.of(CAPABILITY_ID, "ui.canvas.hint");

    /** Reviewed exact Cubism versions this status-bar trust root can serve. */
    public static Set<String> reviewedCubismVersions() {
        return ReviewedVersions.since("5.0.00");
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
        "cubism.ui-canvas-hint.app-controller.current-view-context",
        "cubism.ui-canvas-hint.view-context.class",
        "cubism.ui-canvas-hint.view-context.show-hint",
        "cubism.ui-canvas-hint.view-context.show-hint-with-action",
        "cubism.ui-canvas-hint.position.class",
        "cubism.ui-canvas-hint.position.create",
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
