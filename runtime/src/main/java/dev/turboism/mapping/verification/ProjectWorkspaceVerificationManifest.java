package dev.turboism.mapping.verification;

import java.util.Set;

/** Runtime-owned allowlist for the reviewed Cubism 5.3.02 project/workspace evidence. */
public final class ProjectWorkspaceVerificationManifest {

    public static final String VERIFICATION_ID = "m15.cubism-5.3.02.project-workspace.static";
    public static final String RECORD_SHA256 =
        "182659e0b3ccae047689ade53bf42e0b441e8144d996242b0cb888f0a0c61191";
    public static final String CUBISM_VERSION = "5.3.02";
    public static final String PROFILE_ID = "cubism-5.3.02";
    public static final long ARTIFACT_SIZE = 41922739L;
    public static final String ARTIFACT_SHA256 =
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
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

    /** Authorizes the complete project/workspace slice for an exact reviewed Cubism version. */
    public static boolean authorizes(final VerifiedMemberResolver resolver) {
        if (resolver == null) {
            return false;
        }
        final boolean reviewedVersion = resolver.isExactCubismVersion(CUBISM_VERSION)
            || resolver.isExactCubismVersion(ProjectWorkspaceVerificationManifest52.CUBISM_VERSION);
        return reviewedVersion && resolver.authorizes(
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    static PinnedVerifiedResolverWorkflow.Manifest forArtifact(
        final HostArtifactDigest artifact
    ) {
        if (artifact.size() == ProjectWorkspaceVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(ProjectWorkspaceVerificationManifest52.ARTIFACT_SHA256)) {
            return manifest(
                ProjectWorkspaceVerificationManifest52.VERIFICATION_ID,
                ProjectWorkspaceVerificationManifest52.RECORD_SHA256,
                ProjectWorkspaceVerificationManifest52.CUBISM_VERSION,
                ProjectWorkspaceVerificationManifest52.PROFILE_ID,
                ProjectWorkspaceVerificationManifest52.ARTIFACT_SIZE,
                ProjectWorkspaceVerificationManifest52.ARTIFACT_SHA256
            );
        }
        if (artifact.size() == ARTIFACT_SIZE && artifact.sha256().equals(ARTIFACT_SHA256)) {
            return manifest(
                VERIFICATION_ID,
                RECORD_SHA256,
                CUBISM_VERSION,
                PROFILE_ID,
                ARTIFACT_SIZE,
                ARTIFACT_SHA256
            );
        }
        throw new IllegalArgumentException(
            "host artifact is not a reviewed Cubism project/workspace artifact"
        );
    }

    /** Returns the exact reviewed Cubism version for an admitted project/workspace artifact. */
    public static String versionForArtifact(final HostArtifactDigest artifact) {
        if (artifact.size() == ProjectWorkspaceVerificationManifest52.ARTIFACT_SIZE
            && artifact.sha256().equals(ProjectWorkspaceVerificationManifest52.ARTIFACT_SHA256)) {
            return ProjectWorkspaceVerificationManifest52.CUBISM_VERSION;
        }
        if (artifact.size() == ARTIFACT_SIZE && artifact.sha256().equals(ARTIFACT_SHA256)) {
            return CUBISM_VERSION;
        }
        throw new IllegalArgumentException(
            "host artifact is not a reviewed Cubism project/workspace artifact"
        );
    }

    private static PinnedVerifiedResolverWorkflow.Manifest manifest(
        final String verificationId,
        final String recordSha256,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256,
            cubismVersion,
            profileId,
            artifactSize,
            artifactSha256,
            ADAPTER_SLICE_ID,
            CAPABILITY_IDS,
            REQUIRED_ALIASES
        );
    }

    private ProjectWorkspaceVerificationManifest() {
    }
}
