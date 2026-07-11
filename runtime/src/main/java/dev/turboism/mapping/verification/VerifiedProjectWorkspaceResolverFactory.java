package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole public resolver entrypoint pinned to the reviewed project/workspace trust root. */
public final class VerifiedProjectWorkspaceResolverFactory {

    private static final PinnedVerifiedResolverWorkflow.Manifest MANIFEST =
        new PinnedVerifiedResolverWorkflow.Manifest(
            ProjectWorkspaceVerificationManifest.VERIFICATION_ID,
            ProjectWorkspaceVerificationManifest.RECORD_SHA256,
            ProjectWorkspaceVerificationManifest.CUBISM_VERSION,
            ProjectWorkspaceVerificationManifest.PROFILE_ID,
            ProjectWorkspaceVerificationManifest.ARTIFACT_SIZE,
            ProjectWorkspaceVerificationManifest.ARTIFACT_SHA256,
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES
        );

    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        return workflow.create(reviewedRecord, verifiedArtifact, hostClassLoader, MANIFEST);
    }
}
