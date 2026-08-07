package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the verified Cubism 5.3.02 status-bar slice. */
public final class VerifiedStatusBarResolverFactory {

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
            StatusBarVerificationManifest.forArtifact(
                HostArtifactDigest.from(verifiedArtifact)
            )
        );
    }
}
