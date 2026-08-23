package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/**
 * The outcome of one plugin-initiated mesh edit.
 *
 * <p>Partial success is reported rather than hidden: {@code applied} is what the host actually
 * changed, and {@code rejected} carries a reason per item the runtime refused, most often a
 * stale id that no longer exists in the live mesh.</p>
 */
@PreviewApi
public record MeshEditResult(boolean accepted, List<String> rejected, String failureReason) {

    public MeshEditResult {
        rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected"));
    }

    /** Returns the result for an edit fully accepted by the host. */
    public static MeshEditResult applied() {
        return new MeshEditResult(true, List.of(), null);
    }

    /** Returns the result for an accepted edit with the supplied rejected items. */
    public static MeshEditResult partiallyApplied(final List<String> rejected) {
        return new MeshEditResult(true, rejected, null);
    }

    /** Returns the result for an edit refused by the host for the supplied reason. */
    public static MeshEditResult refused(final String reason) {
        return new MeshEditResult(false, List.of(), Objects.requireNonNull(reason, "reason"));
    }
}
