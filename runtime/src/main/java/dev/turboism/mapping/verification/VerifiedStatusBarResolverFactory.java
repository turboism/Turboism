package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Pinned resolver factory for the reviewed exact Cubism 5.2.03 and 5.3.02
 * status-bar slice; the manifest dispatches on the artifact digest.
 */
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
