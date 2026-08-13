package dev.turboism.mapping.verification;

/** Hash-anchored Editor-model trust-root material reusable by admitted UI providers. */
public record EditorModelAdmissionEvidence(
    String cubismVersion,
    long artifactSize,
    String artifactSha256,
    String adapterSliceId,
    String recordSha256
) {
    public static EditorModelAdmissionEvidence forArtifact(final HostArtifactDigest artifact) {
        final PinnedVerifiedResolverWorkflow.Manifest manifest = EditorModelVerificationManifest.forArtifact(artifact);
        return new EditorModelAdmissionEvidence(
            manifest.cubismVersion(),
            manifest.artifactSize(),
            manifest.artifactSha256(),
            manifest.adapterSliceId(),
            manifest.recordSha256()
        );
    }

    public static EditorModelAdmissionEvidence forResolver(final VerifiedMemberResolver resolver) {
        return switch (resolver.cubismVersion()) {
            case EditorModelVerificationManifest.CUBISM_VERSION -> new EditorModelAdmissionEvidence(
                EditorModelVerificationManifest.CUBISM_VERSION,
                EditorModelVerificationManifest.ARTIFACT_SIZE,
                EditorModelVerificationManifest.ARTIFACT_SHA256,
                EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                EditorModelVerificationManifest.RECORD_SHA256
            );
            case EditorModelVerificationManifest52.CUBISM_VERSION -> new EditorModelAdmissionEvidence(
                EditorModelVerificationManifest52.CUBISM_VERSION,
                EditorModelVerificationManifest52.ARTIFACT_SIZE,
                EditorModelVerificationManifest52.ARTIFACT_SHA256,
                EditorModelVerificationManifest.ADAPTER_SLICE_ID,
                EditorModelVerificationManifest52.RECORD_SHA256
            );
            default -> throw new IllegalArgumentException("Unsupported Editor model resolver version");
        };
    }
}
