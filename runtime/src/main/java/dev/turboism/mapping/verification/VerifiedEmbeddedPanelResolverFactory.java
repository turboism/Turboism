package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for exact-version Cubism embedded-panel selectors. */
public final class VerifiedEmbeddedPanelResolverFactory {

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
            EmbeddedPanelVerificationManifest.forArtifact(
                HostArtifactDigest.from(verifiedArtifact)
            )
        );
    }
}
