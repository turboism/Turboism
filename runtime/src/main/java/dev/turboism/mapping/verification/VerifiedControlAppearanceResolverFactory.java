package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for exact Cubism 5.2.03/5.3.02 control-appearance hooks. */
public final class VerifiedControlAppearanceResolverFactory {
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
            ControlAppearanceVerificationManifest.forArtifact(HostArtifactDigest.from(verifiedArtifact))
        );
    }
}
