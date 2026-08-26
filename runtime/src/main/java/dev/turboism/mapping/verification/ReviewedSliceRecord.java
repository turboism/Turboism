package dev.turboism.mapping.verification;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One reviewed static-verification record bound to one exact Cubism artifact.
 *
 * <p>A capability family declares one record per supported Cubism version and resolves the
 * incoming artifact through {@link #requireReviewed(List, HostArtifactDigest, String)}. Declaring
 * versions as data keeps the supported matrix symmetric: no type or constant may stand for "the
 * other version", and adding a version is a new record rather than a second class.</p>
 *
 * <p>Resolution is exact and fails closed. An artifact matches only when its size and SHA-256
 * both equal a declared reviewed artifact; nothing here falls back to a nearest match, a version
 * range, or a differently reviewed family.</p>
 *
 * @param artifact the exact reviewed host artifact this record is admitted for
 * @param verificationId the reviewed record's stable verification identity
 * @param recordSha256 SHA-256 of the reviewed record bytes under {@code compatibility/cubism/verification/}
 * @param cubismVersion the Cubism version string this record reports for the artifact
 * @param profileId the mapping profile this record's selectors were observed under
 */
public record ReviewedSliceRecord(
    HostArtifactDigest artifact,
    String verificationId,
    String recordSha256,
    String cubismVersion,
    String profileId
) {

    /**
     * Validates that a reviewed record is fully specified.
     *
     * @throws NullPointerException when any component is null
     * @throws IllegalArgumentException when the artifact is not a reviewed Cubism artifact
     */
    public ReviewedSliceRecord {
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(verificationId, "verificationId");
        Objects.requireNonNull(recordSha256, "recordSha256");
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        Objects.requireNonNull(profileId, "profileId");
        if (!ReviewedHostArtifacts.isReviewed(artifact)) {
            throw new IllegalArgumentException(
                "reviewed slice records may only be declared for reviewed Cubism artifacts"
            );
        }
    }

    /**
     * Builds the pinned resolver manifest for this record within its capability family.
     *
     * @param adapterSliceId the family's adapter slice identity
     * @param capabilityIds the family's capability identities
     * @param requiredAliases the selector aliases the family authorises
     * @return a manifest carrying this record's exact version, profile and artifact binding
     */
    PinnedVerifiedResolverWorkflow.Manifest toManifest(
        final String adapterSliceId,
        final Set<String> capabilityIds,
        final Set<String> requiredAliases
    ) {
        return new PinnedVerifiedResolverWorkflow.Manifest(
            verificationId,
            recordSha256,
            cubismVersion,
            profileId,
            artifact.size(),
            artifact.sha256(),
            adapterSliceId,
            capabilityIds,
            requiredAliases
        );
    }

    /**
     * Resolves the reviewed record for an observed artifact, or fails closed.
     *
     * @param records the family's reviewed records, one per supported Cubism version
     * @param artifact the observed host artifact identity
     * @param familyLabel the family name used in the fail-closed diagnostic
     * @return the single record whose reviewed artifact matches exactly
     * @throws IllegalArgumentException when no reviewed record admits the artifact
     */
    static ReviewedSliceRecord requireReviewed(
        final List<ReviewedSliceRecord> records,
        final HostArtifactDigest artifact,
        final String familyLabel
    ) {
        Objects.requireNonNull(artifact, "artifact");
        for (final ReviewedSliceRecord record : records) {
            if (record.artifact().equals(artifact)) {
                return record;
            }
        }
        throw new IllegalArgumentException(
            "host artifact is not a reviewed Cubism " + familyLabel + " artifact"
        );
    }
}
