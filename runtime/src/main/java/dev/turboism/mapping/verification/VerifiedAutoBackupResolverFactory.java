package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the verified Cubism auto-backup slices (5.3.02 and 5.2.03). */
public final class VerifiedAutoBackupResolverFactory {

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
            AutoBackupVerificationManifest.forArtifact(
                HostArtifactDigest.from(verifiedArtifact)
            )
        );
    }
}
