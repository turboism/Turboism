package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the verified Cubism Editor main-toolbar slice. */
public final class VerifiedMainToolbarResolverFactory {

    private static final PinnedVerifiedResolverWorkflow.Manifest MANIFEST =
        new PinnedVerifiedResolverWorkflow.Manifest(
            MainToolbarVerificationManifest.VERIFICATION_ID,
            MainToolbarVerificationManifest.RECORD_SHA256,
            MainToolbarVerificationManifest.CUBISM_VERSION,
            MainToolbarVerificationManifest.PROFILE_ID,
            MainToolbarVerificationManifest.ARTIFACT_SIZE,
            MainToolbarVerificationManifest.ARTIFACT_SHA256,
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            MainToolbarVerificationManifest.REQUIRED_ALIASES
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
