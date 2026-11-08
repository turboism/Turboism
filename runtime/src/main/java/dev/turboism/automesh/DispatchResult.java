package dev.turboism.automesh;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.List;
import java.util.Objects;

/**
 * Terminal outcome of one started custom auto-mesh run.
 *
 * <p>{@link #writtenTargets()} stays empty unless the run committed, so a caller can never report a
 * write that the Editor did not keep. {@link #message()} is the text the native popup shows: the
 * algorithm's own reason for a rejection, or the fault summary of a failed run.</p>
 *
 * @param status the terminal status
 * @param message the user-facing text; empty when the run has nothing to report
 * @param writtenTargets the ArtMeshes the committed transaction replaced
 */
public record DispatchResult(Status status, String message, List<ArtMeshId> writtenTargets) {

    public DispatchResult {
        status = Objects.requireNonNull(status, "status");
        message = Objects.requireNonNull(message, "message");
        writtenTargets = List.copyOf(Objects.requireNonNull(writtenTargets, "writtenTargets"));
    }

    /** Terminal status of one started custom run. */
    public enum Status {

        /** Every target was replaced inside one committed Editor transaction. */
        APPLIED,

        /** The run produced no applicable geometry: the user sees {@code message} and no write happened. */
        REJECTED,

        /** The user cancelled the run, so nothing was written. */
        CANCELLED,

        /** The runtime could not complete the run: nothing was written and {@code message} explains why. */
        FAILED
    }
}
