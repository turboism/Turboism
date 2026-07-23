package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole public resolver entrypoint pinned to the reviewed project/workspace trust root. */
public final class VerifiedProjectWorkspaceResolverFactory {

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
            ProjectWorkspaceVerificationManifest.forArtifact(
                HostArtifactDigest.from(verifiedArtifact)
            )
        );
    }
}
