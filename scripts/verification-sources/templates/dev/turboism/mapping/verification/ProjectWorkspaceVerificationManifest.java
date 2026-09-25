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

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "${record:cubism-5.2.03-project-workspace.json:cubismVersion}";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "${record:cubism-5.3.02-project-workspace.json:cubismVersion}";

    /** Cubism version reported for the reviewed 5.3.03 read-only artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "${record:cubism-5.3.03-project-workspace.json:cubismVersion}";

    /** Reviewed project/workspace record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "${record:cubism-5.2.03-project-workspace.json:verificationId}",
        "${record:cubism-5.2.03-project-workspace.json:sha256}",
        CUBISM_VERSION_5_2_03,
        "${record:cubism-5.2.03-project-workspace.json:profileId}"
    );

    /** Reviewed project/workspace record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "${record:cubism-5.3.02-project-workspace.json:verificationId}",
        "${record:cubism-5.3.02-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_02,
        "${record:cubism-5.3.02-project-workspace.json:profileId}"
    );

    /** Reviewed project/workspace read record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "${record:cubism-5.3.03-project-workspace.json:verificationId}",
        "${record:cubism-5.3.03-project-workspace.json:sha256}",
        CUBISM_VERSION_5_3_03,
        "${record:cubism-5.3.03-project-workspace.json:profileId}"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(RECORD_5_2_03, RECORD_5_3_02, RECORD_5_3_03);

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
        final boolean reviewedVersion = resolver.isExactCubismVersion(CUBISM_VERSION_5_3_03)
            || resolver.isExactCubismVersion(CUBISM_VERSION_5_3_02)
            || resolver.isExactCubismVersion(CUBISM_VERSION_5_2_03);
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
