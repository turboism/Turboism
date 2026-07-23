package dev.turboism.mapping.verification;

import java.io.IOException;
import java.nio.file.Path;

/** Pinned resolver factory for the verified Editor model read/write slice. */
public final class VerifiedEditorModelResolverFactory {

    private static final PinnedVerifiedResolverWorkflow.Manifest MANIFEST =
        new PinnedVerifiedResolverWorkflow.Manifest(
            EditorModelVerificationManifest.VERIFICATION_ID,
            EditorModelVerificationManifest.RECORD_SHA256,
            EditorModelVerificationManifest.CUBISM_VERSION,
            EditorModelVerificationManifest.PROFILE_ID,
            EditorModelVerificationManifest.ARTIFACT_SIZE,
            EditorModelVerificationManifest.ARTIFACT_SHA256,
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest.CAPABILITY_IDS,
            EditorModelVerificationManifest.REQUIRED_ALIASES
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
