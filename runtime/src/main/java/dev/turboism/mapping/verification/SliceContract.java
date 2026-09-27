package dev.turboism.mapping.verification;

import java.util.Objects;
import java.util.Set;

/**
 * The binding decided for one adapter slice: which reviewed contract the host
 * artifact was admitted under, and whether that admission is exact
 * (byte-identical reviewed artifact) or compatibility (structural selector
 * match against an unreviewed build).
 *
 * @param sliceId stable slice key such as {@code "editor-model"}
 * @param sourceVersion the reviewed Cubism version the bound record was authored against
 * @param recordFileName embedded verification record resource name under
 *     {@code META-INF/turboism/verification/}
 * @param recordSha256 pinned SHA-256 of the bound record bytes
 * @param verificationId the bound record's stable verification identity
 * @param adapterSliceId the bound record's adapter-slice identity
 * @param declaredVersion the version the host itself declared at probe time
 * @param declaredBuild the build the host itself declared at probe time
 * @param probedArtifact measured identity of this slice's artifact when its contract was checked
 * @param compatible {@code true} when the host artifact is unreviewed and the
 *     slice was admitted structurally; {@code false} for exact reviewed
 *     bindings
 * @param capabilities the capability ids actually admitted under this binding:
 *     the bound record's declared {@code capabilityIds}, minus any ids whose
 *     record-declared {@code capabilityConditions} (structure, declared
 *     generation, named hook contract) were not satisfied by this binding
 * @param droppedCapabilities the capability ids this binding refused, paired
 *     with the unsatisfied condition that dropped them
 */
public record SliceContract(
    String sliceId,
    String sourceVersion,
    String recordFileName,
    String recordSha256,
    String verificationId,
    String adapterSliceId,
    String declaredVersion,
    int declaredBuild,
    HostArtifactDigest probedArtifact,
    boolean compatible,
    Set<String> capabilities,
    java.util.Map<String, String> droppedCapabilities
) {
    public SliceContract {
        sliceId = requireText(sliceId, "sliceId");
        sourceVersion = requireText(sourceVersion, "sourceVersion");
        recordFileName = requireText(recordFileName, "recordFileName");
        recordSha256 = requireSha(recordSha256);
        verificationId = requireText(verificationId, "verificationId");
        adapterSliceId = requireText(adapterSliceId, "adapterSliceId");
        declaredVersion = requireText(declaredVersion, "declaredVersion");
        if (declaredBuild <= 0) {
            throw new IllegalArgumentException("declaredBuild must be positive");
        }
        probedArtifact = Objects.requireNonNull(probedArtifact, "probedArtifact");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        droppedCapabilities = java.util.Map.copyOf(
            Objects.requireNonNull(droppedCapabilities, "droppedCapabilities")
        );
    }

    /**
     * Whether this compatibility binding landed on the host's own declared
     * generation: the declared version/build is a reviewed release and
     * this slice's contract verified against that generation's record.
     * Declared-bound slices may host runtime hooks and transaction bridges;
     * slices bound to another generation carry structural evidence only.
     *
     * @return {@code true} only for a compatible slice bound to the declared
     *     reviewed generation
     */
    public boolean declaredGenerationBound() {
        return compatible
            && sourceVersion.equals(declaredVersion)
            && ReviewedCubismReleases.isReviewed(declaredVersion, declaredBuild)
            && ReviewedHostArtifacts.admitsFullRuntime(declaredVersion);
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String requireSha(final String value) {
        final String normalized = requireText(value, "recordSha256").toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("recordSha256 must be 64 lowercase hexadecimal characters");
        }
        return normalized;
    }
}
