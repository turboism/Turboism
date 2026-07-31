package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the verified Cubism bounding-box overlay slice. */
public final class VerifiedBoundingBoxOverlayButtonResolverFactory {

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
            BoundingBoxOverlayButtonVerificationManifest.forArtifact(
                HostArtifactDigest.from(verifiedArtifact)
            )
        );
    }
}
