package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole resolver entrypoint pinned to reviewed exact-version workspace-control records. */
public final class VerifiedWorkspaceControlResolverFactory {
    private final PinnedVerifiedResolverWorkflow workflow = new PinnedVerifiedResolverWorkflow();

    public VerifiedMemberResolver create(
        final Path reviewedRecord,
        final Path verifiedArtifact,
        final ClassLoader hostClassLoader
    ) throws IOException {
        return workflow.create(
            reviewedRecord,
            verifiedArtifact,
            hostClassLoader,
            WorkspaceControlVerificationManifest.forArtifact(HostArtifactDigest.from(verifiedArtifact))
        );
    }
}
