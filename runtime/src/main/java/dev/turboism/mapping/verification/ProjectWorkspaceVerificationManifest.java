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
    public static final String CUBISM_VERSION_5_2_03 = "5.2.03";

    /** Cubism version reported for the reviewed 5.3.02 artifact. */
    public static final String CUBISM_VERSION_5_3_02 = "5.3.02";

    /** Cubism version reported for the reviewed 5.3.03 read-only artifact. */
    public static final String CUBISM_VERSION_5_3_03 = "5.3.03";

    /** Reviewed project/workspace record admitted for exact Cubism 5.2.03. */
    public static final ReviewedSliceRecord RECORD_5_2_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_2_03,
        "m15.cubism-5.2.03.project-workspace.static",
        "59ac1ee40d386aed22b6f3f8c6eb0fe876c5af69190affd7f0c00209d1f12de4",
        CUBISM_VERSION_5_2_03,
        "cubism-5.2.03"
    );

    /** Reviewed project/workspace record admitted for exact Cubism 5.3.02. */
    public static final ReviewedSliceRecord RECORD_5_3_02 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_02,
        "m15.cubism-5.3.02.project-workspace.static",
        "182659e0b3ccae047689ade53bf42e0b441e8144d996242b0cb888f0a0c61191",
        CUBISM_VERSION_5_3_02,
        "cubism-5.3.02"
    );

    /** Reviewed project/workspace read record admitted for exact Cubism 5.3.03. */
    public static final ReviewedSliceRecord RECORD_5_3_03 = new ReviewedSliceRecord(
        ReviewedHostArtifacts.CUBISM_5_3_03,
        "m15.cubism-5.3.03.project-workspace.static",
        "d7f45e0c7d70925b4c77db18022b06ee4f089bc7b1cbe585ef311efa754f168e",
        CUBISM_VERSION_5_3_03,
        "cubism-5.3.03"
    );

    private static final List<ReviewedSliceRecord> RECORDS = List.of(RECORD_5_2_03, RECORD_5_3_02, RECORD_5_3_03);

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
