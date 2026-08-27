package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Set;

/**
 * Runtime-owned allowlist for the reviewed Cubism project/workspace evidence.
 *
 * <p>Both supported Cubism versions are declared symmetrically as {@link ReviewedSliceRecord}
 * data; every other artifact fails closed.</p>
 */
public final class ProjectWorkspaceVerificationManifest {

    /** Cubism version reported for the reviewed 5.2.03 artifact. */
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 static record. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed project/workspace record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "m15.cubism-5.2.03.project-workspace.static",
        "38a9da7d0d6a37b7b37a54499cb788341f5a081bb545c6aafabf3e0fd262ea3f",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed project/workspace record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "m15.cubism-5.3.02.project-workspace.static",
        "902e3284dad2180a4211f87b777df67fe031a98a48c6fa339acd5602f33ef38b",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed static project/workspace record identified for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "m15.cubism-5.3.03.project-workspace.static",
        "a238d1ef701f59130d792b2b6ada3961ab9541f6cf5236bbed25d5f9d558eab2",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(
        RECORD_5_2_03,
        RECORD_5_3_02,
        RECORD_5_3_03
    );

    public static final String ADAPTER_SLICE_ID = "adapter.project-workspace.readonly";
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
        final boolean reviewedVersion = resolver.isExactCubismVersion(CUBISM_VERSION_5_3_02)
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
