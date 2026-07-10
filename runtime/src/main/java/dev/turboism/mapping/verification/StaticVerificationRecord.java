package dev.turboism.mapping.verification;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Parsed, schema-validated static verification evidence. */
record StaticVerificationRecord(
    String verificationId,
    String adapterSliceId,
    List<String> capabilityIds,
    String cubismVersion,
    String profileId,
    HostArtifactFingerprint artifact,
    String evidencePath,
    String owner,
    String verifiedBy,
    Instant verifiedAt,
    String safeMode,
    List<StaticSelector> selectors
) {
    public StaticVerificationRecord {
        verificationId = requireText(verificationId, "verificationId");
        adapterSliceId = requireText(adapterSliceId, "adapterSliceId");
        capabilityIds = List.copyOf(Objects.requireNonNull(capabilityIds, "capabilityIds"));
        if (capabilityIds.isEmpty()) {
            throw new IllegalArgumentException("capabilityIds must not be empty");
        }
        if (new java.util.HashSet<>(capabilityIds).size() != capabilityIds.size()) {
            throw new IllegalArgumentException("capabilityIds must not contain duplicates");
        }
        cubismVersion = requireText(cubismVersion, "cubismVersion");
        profileId = requireText(profileId, "profileId");
        artifact = Objects.requireNonNull(artifact, "artifact");
        evidencePath = requireText(evidencePath, "evidencePath");
        owner = requireText(owner, "owner");
        verifiedBy = requireText(verifiedBy, "verifiedBy");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        safeMode = requireText(safeMode, "safeMode");
        selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        if (selectors.isEmpty()) {
            throw new IllegalArgumentException("selectors must not be empty");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
