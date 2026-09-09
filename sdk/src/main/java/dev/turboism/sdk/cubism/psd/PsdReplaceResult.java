package dev.turboism.sdk.cubism.psd;

import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.TextureRelationsSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Observed outcome of replacing an explicitly targeted raw image through Cubism's native matcher.
 *
 * <p>APPLIED requires a post-native observation identifying the current raw image. A native void
 * return is insufficient. PARTIAL_FAILURE means mutation/rollback could not be established: pause
 * automatic importing, retain temporary files, and never automatically retry the mutation. Native
 * owns the single Undo boundary; these values create neither transactions nor write authority.</p>
 *
 * @param diagnostic runtime-sanitized explanation
 * @param before requested raw-image identity, not proof of a successful preflight
 * @param after reobserved current raw-image identity, empty when not established
 * @param consumedRevision version consumed only after verified application
 * @param relations optional available post-operation relation snapshot
 */
public record PsdReplaceResult(
    Status status,
    String diagnostic,
    RawImageId before,
    Optional<RawImageId> after,
    Optional<PsdFileRevision> consumedRevision,
    Optional<TextureRelationsSnapshot> relations
) {
    public PsdReplaceResult {
        status = Objects.requireNonNull(status, "status");
        diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
        consumedRevision = Objects.requireNonNull(consumedRevision, "consumedRevision");
        relations = Objects.requireNonNull(relations, "relations");
        if (status == Status.APPLIED) {
            if (after.isEmpty() || consumedRevision.isEmpty()) {
                throw new IllegalArgumentException("applied result requires observed target and consumed revision");
            }
        } else if (consumedRevision.isPresent()) {
            throw new IllegalArgumentException("unverified application cannot consume a revision");
        }
        if (relations.isPresent()) {
            final TextureRelationsSnapshot snapshot = relations.orElseThrow();
            if (!snapshot.isAvailable()) {
                throw new IllegalArgumentException("unavailable relations must be represented by an empty optional");
            }
            if (after.isPresent() && snapshot.rawImage(after.orElseThrow()).isEmpty()) {
                throw new IllegalArgumentException("observed target must belong to supplied relation snapshot");
            }
        }
    }

    /** Outcome of one explicit raw-image replacement request. */
    public enum Status {
        APPLIED,
        UNAVAILABLE,
        STALE_TARGET,
        REJECTED,
        FAILED,
        PARTIAL_FAILURE
    }
}
