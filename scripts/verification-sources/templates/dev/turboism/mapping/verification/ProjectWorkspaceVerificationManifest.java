package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime-owned allowlist for the reviewed Cubism project/workspace evidence.
 *
 * <p>All admitted Cubism versions are declared symmetrically as {@link ReviewedSliceRecord}
 * data; every other artifact fails closed.</p>
 */
public final class ProjectWorkspaceVerificationManifest {

    /** Cubism version reported for the reviewed 5.0.00 artifact. */
    public static final String CUBISM_VERSION_5_0_00 = "${record:cubism-5.0.00-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.01 artifact. */
    public static final String CUBISM_VERSION_5_0_01 = "${record:cubism-5.0.01-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.02 artifact. */
    public static final String CUBISM_VERSION_5_0_02 = "${record:cubism-5.0.02-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.03 artifact. */
    public static final String CUBISM_VERSION_5_0_03 = "${record:cubism-5.0.03-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.04 artifact. */
    public static final String CUBISM_VERSION_5_0_04 = "${record:cubism-5.0.04-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.05 artifact. */
    public static final String CUBISM_VERSION_5_0_05 = "${record:cubism-5.0.05-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.0.06 artifact. */
    public static final String CUBISM_VERSION_5_0_06 = "${record:cubism-5.0.06-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.00 artifact. */
    public static final String CUBISM_VERSION_5_1_00 = "${record:cubism-5.1.00-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.01 artifact. */
    public static final String CUBISM_VERSION_5_1_01 = "${record:cubism-5.1.01-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.02 artifact. */
    public static final String CUBISM_VERSION_5_1_02 = "${record:cubism-5.1.02-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.1.03 artifact. */
    public static final String CUBISM_VERSION_5_1_03 = "${record:cubism-5.1.03-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.00 artifact. */
    public static final String CUBISM_VERSION_5_2_00 = "${record:cubism-5.2.00-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.01 artifact. */
    public static final String CUBISM_VERSION_5_2_01 = "${record:cubism-5.2.01-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.02 artifact. */
    public static final String CUBISM_VERSION_5_2_02 = "${record:cubism-5.2.02-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "${record:cubism-5.2.03-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.00 artifact. */
    public static final String CUBISM_VERSION_5_3_00 = "${record:cubism-5.3.00-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.01 artifact. */
    public static final String CUBISM_VERSION_5_3_01 = "${record:cubism-5.3.01-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "${record:cubism-5.3.02-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.03 artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "${record:cubism-5.3.03-project-workspace.json:cubismVersion}";
    /** Cubism version reported for the reviewed 5.3.04 artifact. */
    public static final String CUBISM_VERSION_5_3_04 = "${record:cubism-5.3.04-project-workspace.json:cubismVersion}";

    /** Reviewed record admitted for exact Cubism 5.0.00. */
    public static final ReviewedSliceRecord RECORD_5_0_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_00,
        "${record:cubism-5.0.00-project-workspace.json:verificationId}",
        "${record:cubism-5.0.00-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_00,
        "${record:cubism-5.0.00-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.01. */
    public static final ReviewedSliceRecord RECORD_5_0_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_01,
        "${record:cubism-5.0.01-project-workspace.json:verificationId}",
        "${record:cubism-5.0.01-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_01,
        "${record:cubism-5.0.01-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.02. */
    public static final ReviewedSliceRecord RECORD_5_0_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_02,
        "${record:cubism-5.0.02-project-workspace.json:verificationId}",
        "${record:cubism-5.0.02-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_02,
        "${record:cubism-5.0.02-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.03. */
    public static final ReviewedSliceRecord RECORD_5_0_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_03,
        "${record:cubism-5.0.03-project-workspace.json:verificationId}",
        "${record:cubism-5.0.03-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_03,
        "${record:cubism-5.0.03-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.04. */
    public static final ReviewedSliceRecord RECORD_5_0_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_04,
        "${record:cubism-5.0.04-project-workspace.json:verificationId}",
        "${record:cubism-5.0.04-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_04,
        "${record:cubism-5.0.04-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.05. */
    public static final ReviewedSliceRecord RECORD_5_0_05 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_05,
        "${record:cubism-5.0.05-project-workspace.json:verificationId}",
        "${record:cubism-5.0.05-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_05,
        "${record:cubism-5.0.05-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.0.06. */
    public static final ReviewedSliceRecord RECORD_5_0_06 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_0_06,
        "${record:cubism-5.0.06-project-workspace.json:verificationId}",
        "${record:cubism-5.0.06-project-workspace.json:sha256}",
        CUBISM_VERSION_5_0_06,
        "${record:cubism-5.0.06-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.00. */
    public static final ReviewedSliceRecord RECORD_5_1_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_00,
        "${record:cubism-5.1.00-project-workspace.json:verificationId}",
        "${record:cubism-5.1.00-project-workspace.json:sha256}",
        CUBISM_VERSION_5_1_00,
        "${record:cubism-5.1.00-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.01. */
    public static final ReviewedSliceRecord RECORD_5_1_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_01,
        "${record:cubism-5.1.01-project-workspace.json:verificationId}",
        "${record:cubism-5.1.01-project-workspace.json:sha256}",
        CUBISM_VERSION_5_1_01,
        "${record:cubism-5.1.01-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.02. */
    public static final ReviewedSliceRecord RECORD_5_1_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_02,
        "${record:cubism-5.1.02-project-workspace.json:verificationId}",
        "${record:cubism-5.1.02-project-workspace.json:sha256}",
        CUBISM_VERSION_5_1_02,
        "${record:cubism-5.1.02-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.1.03. */
    public static final ReviewedSliceRecord RECORD_5_1_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_1_03,
        "${record:cubism-5.1.03-project-workspace.json:verificationId}",
        "${record:cubism-5.1.03-project-workspace.json:sha256}",
        CUBISM_VERSION_5_1_03,
        "${record:cubism-5.1.03-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.00. */
    public static final ReviewedSliceRecord RECORD_5_2_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_00,
        "${record:cubism-5.2.00-project-workspace.json:verificationId}",
        "${record:cubism-5.2.00-project-workspace.json:sha256}",
        CUBISM_VERSION_5_2_00,
        "${record:cubism-5.2.00-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.01. */
    public static final ReviewedSliceRecord RECORD_5_2_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_01,
        "${record:cubism-5.2.01-project-workspace.json:verificationId}",
        "${record:cubism-5.2.01-project-workspace.json:sha256}",
        CUBISM_VERSION_5_2_01,
        "${record:cubism-5.2.01-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.02. */
    public static final ReviewedSliceRecord RECORD_5_2_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_02,
        "${record:cubism-5.2.02-project-workspace.json:verificationId}",
        "${record:cubism-5.2.02-project-workspace.json:sha256}",
        CUBISM_VERSION_5_2_02,
        "${record:cubism-5.2.02-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "${record:cubism-5.2.03-project-workspace.json:verificationId}",
        "${record:cubism-5.2.03-project-workspace.json:sha256}",
        CUBISM_VERSION_5_2_03,
        "${record:cubism-5.2.03-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.00. */
    public static final ReviewedSliceRecord RECORD_5_3_00 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_00,
        "${record:cubism-5.3.00-project-workspace.json:verificationId}",
        "${record:cubism-5.3.00-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_00,
        "${record:cubism-5.3.00-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.01. */
    public static final ReviewedSliceRecord RECORD_5_3_01 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_01,
        "${record:cubism-5.3.01-project-workspace.json:verificationId}",
        "${record:cubism-5.3.01-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_01,
        "${record:cubism-5.3.01-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "${record:cubism-5.3.02-project-workspace.json:verificationId}",
        "${record:cubism-5.3.02-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_02,
        "${record:cubism-5.3.02-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "${record:cubism-5.3.03-project-workspace.json:verificationId}",
        "${record:cubism-5.3.03-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_03,
        "${record:cubism-5.3.03-project-workspace.json:profileId}"
    );
    /** Reviewed record admitted for exact Cubism 5.3.04. */
    public static final ReviewedSliceRecord RECORD_5_3_04 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_04,
        "${record:cubism-5.3.04-project-workspace.json:verificationId}",
        "${record:cubism-5.3.04-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_04,
        "${record:cubism-5.3.04-project-workspace.json:profileId}"
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






    public static final String ADAPTER_SLICE_ID = "${record:cubism-5.2.03-project-workspace.json:adapterSliceId}";
    public static final Set<String> CAPABILITY_IDS = Set.of(
        "cubism.project.read",
        "cubism.workspace.read"
    );
    public static final Set<String> REQUIRED_ALIASES = Set.of(
        "cubism.app-controller.class",
        "cubism.project.class",
        "cubism.document.class",
        "cubism.file-content.class",
        "cubism.main-frame.class",
        "cubism.dock-wrapper.class",
        "cubism.workspace.class",
        "cubism.id.class",
        "cubism.guid.class",
        "cubism.app-controller.instance",
        "cubism.app-controller.current-project",
        "cubism.app-controller.current-document",
        "cubism.app-controller.main-frame",
        "cubism.project.documents",
        "cubism.document.file-content",
        "cubism.file-content.file",
        "cubism.main-frame.dock-manager",
        "cubism.dock-wrapper.last-workspace",
        "cubism.workspace.id",
        "cubism.workspace.name",
        "cubism.workspace.guid",
        "cubism.id.value",
        "cubism.guid.value"
    );

    /** Reviewed exact Cubism versions this project/workspace trust root can serve. */
    public static Set<String> reviewedCubismVersions() {
        return Set.of(
            CUBISM_VERSION_5_0_00,
            CUBISM_VERSION_5_0_01,
            CUBISM_VERSION_5_0_02,
            CUBISM_VERSION_5_0_03,
            CUBISM_VERSION_5_0_04,
            CUBISM_VERSION_5_0_05,
            CUBISM_VERSION_5_0_06,
            CUBISM_VERSION_5_1_00,
            CUBISM_VERSION_5_1_01,
            CUBISM_VERSION_5_1_02,
            CUBISM_VERSION_5_1_03,
            CUBISM_VERSION_5_2_00,
            CUBISM_VERSION_5_2_01,
            CUBISM_VERSION_5_2_02,
            CUBISM_VERSION_5_2_03,
            CUBISM_VERSION_5_3_00,
            CUBISM_VERSION_5_3_01,
            CUBISM_VERSION_5_3_02,
            CUBISM_VERSION_5_3_03,
            CUBISM_VERSION_5_3_04
        );
    }

    /**
     * Authorizes the complete project/workspace slice for an exact reviewed Cubism version.
     *
     * @param resolver the resolver to test, may be null
     * @return {@code true} only when the resolver reports one reviewed version and authorises
     *     the full slice, capability and alias set
     */
    public static boolean authorizes(final VerifiedMemberResolver resolver) {
        if (resolver == null) {
            return false;
        }
        final boolean reviewedVersion = reviewedCubismVersions().stream()
            .anyMatch(resolver::isAdmittedCubismVersion);
        return reviewedVersion && resolver.authorizes(
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "project/workspace")
            .toManifest(ADAPTER_SLICE_ID, CAPABILITY_IDS, REQUIRED_ALIASES);
    }

    /**
     * Returns the exact reviewed Cubism version for an admitted project/workspace artifact.
     *
     * @param artifact the observed host artifact identity
     * @return the reviewed Cubism version string for that artifact
     * @throws IllegalArgumentException when the artifact is not reviewed for this family
     */
    public static String versionForArtifact(final HostArtifactDigest artifact) {
        return ReviewedSliceRecord.requireReviewed(RECORDS, artifact, "project/workspace")
            .cubismVersion();
    }

    private ProjectWorkspaceVerificationManifest() {
    }
}
