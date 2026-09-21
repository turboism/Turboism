package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.CubismEditor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Reads and switches the Editor's workspace layout.
 *
 * <p>Every operation is asynchronous because the underlying host calls must run on the Cubism host
 * thread; the returned stages complete on whatever thread the implementation uses, so callers that
 * touch UI afterwards must hop back themselves. Implementations report host refusal through
 * {@link WorkspaceOperationResult} rather than by failing the stage.
 */
public interface WorkspaceService {

    /**
     * Reads the current workspace and the set of workspaces the host offers.
     *
     * @return a stage completing with a snapshot; the snapshot reports
     *         {@link WorkspaceStatus.Availability#UNAVAILABLE} rather than failing when the host
     *         cannot be queried
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    CompletionStage<WorkspaceStatus> current();

    /**
     * Applies the named workspace layout.
     *
     * @param workspaceId the workspace to apply, non-null
     * @return a stage completing with the outcome; an unknown id yields
     *         {@link WorkspaceOperationResult.Outcome#NOT_FOUND} rather than a failed stage
     */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    CompletionStage<WorkspaceOperationResult> switchTo(WorkspaceId workspaceId);

    /**
     * Stores the current layout as the default for the active workspace.
     *
     * @return a stage completing with the outcome of the save
     */
    CompletionStage<WorkspaceOperationResult> updateDefault();

    /**
     * Discards user changes and restores the active workspace to its stored default layout.
     *
     * @return a stage completing with the outcome of the reset
     */
    CompletionStage<WorkspaceOperationResult> resetToDefault();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * A service for hosts that expose no workspace control.
     *
     * <p>Every operation completes immediately with
     * {@link WorkspaceOperationResult.Outcome#UNAVAILABLE} and the diagnostic code
     * {@code workspace.unavailable}; nothing is ever sent to the host. Argument validation still
     * applies, so {@code switchTo(null)} still throws.
     *
     * @return a stateless no-op service that never touches the host
     */
    static WorkspaceService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls that report outcomes complete with the structured unavailability result; and queries report empty results. */
    enum Unavailable implements WorkspaceService {
        INSTANCE;

        private static final WorkspaceStatus STATUS = new WorkspaceStatus(
            WorkspaceStatus.Availability.UNAVAILABLE,
            Optional.empty(),
            List.of(),
            Optional.of("workspace.unavailable")
        );

        private static final WorkspaceOperationResult RESULT = new WorkspaceOperationResult(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            STATUS,
            Optional.of("workspace.unavailable")
        );

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public CompletionStage<WorkspaceStatus> current() {
            return CompletableFuture.completedFuture(STATUS);
        }

        @Override public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
            Objects.requireNonNull(workspaceId, "workspaceId");
            return CompletableFuture.completedFuture(RESULT);
        }

        @Override public CompletionStage<WorkspaceOperationResult> updateDefault() {
            return CompletableFuture.completedFuture(RESULT);
        }

        @Override public CompletionStage<WorkspaceOperationResult> resetToDefault() {
            return CompletableFuture.completedFuture(RESULT);
        }
    }
}
