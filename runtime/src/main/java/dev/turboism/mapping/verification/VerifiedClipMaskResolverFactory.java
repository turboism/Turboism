package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Sole public resolver entrypoint pinned to the reviewed clip-mask trust root. */
public final class VerifiedClipMaskResolverFactory {

    private static final PinnedVerifiedResolverWorkflow.Manifest MANIFEST =
        new PinnedVerifiedResolverWorkflow.Manifest(
            ClipMaskVerificationManifest.VERIFICATION_ID,
            ClipMaskVerificationManifest.RECORD_SHA256,
            ClipMaskVerificationManifest.CUBISM_VERSION,
            ClipMaskVerificationManifest.PROFILE_ID,
            ClipMaskVerificationManifest.ARTIFACT_SIZE,
            ClipMaskVerificationManifest.ARTIFACT_SHA256,
            ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
            ClipMaskVerificationManifest.CAPABILITY_IDS,
            ClipMaskVerificationManifest.REQUIRED_ALIASES
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
