package dev.turboism.mapping.verification;

/**
 * Hash-anchored Editor-model trust-root material reusable by admitted UI providers.
 *
 * @param cubismVersion the reviewed Cubism version
 * @param artifactSize the reviewed artifact byte size
 * @param artifactSha256 the reviewed artifact SHA-256
 * @param adapterSliceId the Editor-model adapter slice identity
 * @param recordSha256 SHA-256 of the reviewed record bytes
 */
public record EditorModelAdmissionEvidence(
    String cubismVersion,
    long artifactSize,
    String artifactSha256,
    String adapterSliceId,
    String recordSha256
) {
    /**
     * Returns the admission evidence for an observed host artifact.
     *
     * @param artifact the observed host artifact identity
     * @return evidence bound to the reviewed record for that exact artifact
     * @throws IllegalArgumentException when the artifact is not a reviewed Editor-model artifact
     */
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

    /**
     * Returns the admission evidence matching an admitted resolver's exact Cubism version.
     *
     * @param resolver a resolver already admitted against a reviewed Editor-model record
     * @return evidence for that resolver's reviewed version
     * @throws IllegalArgumentException when the resolver reports an unsupported version
     */
    public static EditorModelAdmissionEvidence forResolver(final VerifiedMemberResolver resolver) {
        return switch (resolver.cubismVersion()) {
            case EditorModelVerificationManifest.CUBISM_VERSION_5_3_02 ->
                of(EditorModelVerificationManifest.RECORD_5_3_02);
            case EditorModelVerificationManifest.CUBISM_VERSION_5_2_03 ->
                of(EditorModelVerificationManifest.RECORD_5_2_03);
            default -> throw new IllegalArgumentException("Unsupported Editor model resolver version");
        };
    }

    private static EditorModelAdmissionEvidence of(final ReviewedSliceRecord record) {
        return new EditorModelAdmissionEvidence(
            record.cubismVersion(),
            record.artifact().size(),
            record.artifact().sha256(),
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            record.recordSha256()
        );
    }
}
