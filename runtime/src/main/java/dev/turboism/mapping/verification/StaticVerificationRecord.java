package dev.turboism.mapping.verification;

import dev.turboism.internal.Requirements;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Parsed, schema-validated static verification evidence. */
record StaticVerificationRecord(
        String verificationId,
        String adapterSliceId,
        List<String> capabilityIds,
        java.util.Map<String, List<String>> capabilityConditions,
        String cubismVersion,
        String profileId,
        HostArtifactFingerprint artifact,
        String evidencePath,
        String owner,
        String verifiedBy,
        Instant verifiedAt,
        String safeMode,
        List<StaticSelector> selectors) {
    public StaticVerificationRecord {
        verificationId = Requirements.text(verificationId, "verificationId");
        adapterSliceId = Requirements.text(adapterSliceId, "adapterSliceId");
        capabilityIds = List.copyOf(Objects.requireNonNull(capabilityIds, "capabilityIds"));
        if (capabilityIds.isEmpty()) {
            throw new IllegalArgumentException("capabilityIds must not be empty");
        }
        if (new java.util.HashSet<>(capabilityIds).size() != capabilityIds.size()) {
            throw new IllegalArgumentException("capabilityIds must not contain duplicates");
        }
        capabilityConditions =
                java.util.Map.copyOf(Objects.requireNonNull(capabilityConditions, "capabilityConditions"));
        cubismVersion = Requirements.text(cubismVersion, "cubismVersion");
        profileId = Requirements.text(profileId, "profileId");
        artifact = Objects.requireNonNull(artifact, "artifact");
        evidencePath = Requirements.text(evidencePath, "evidencePath");
        owner = Requirements.text(owner, "owner");
        verifiedBy = Requirements.text(verifiedBy, "verifiedBy");
        verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        safeMode = Requirements.text(safeMode, "safeMode");
        selectors = List.copyOf(Objects.requireNonNull(selectors, "selectors"));
        if (selectors.isEmpty()) {
            throw new IllegalArgumentException("selectors must not be empty");
        }
    }
}
