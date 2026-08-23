package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

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
@PreviewApi
public interface WorkspaceService {

    /**
     * Reads the current workspace and the set of workspaces the host offers.
     *
     * @return a stage completing with a snapshot; the snapshot reports
     *         {@link WorkspaceStatus.Availability#UNAVAILABLE} rather than failing when the host
     *         cannot be queried
     */
    CompletionStage<WorkspaceStatus> current();

    /**
     * Applies the named workspace layout.
     *
     * @param workspaceId the workspace to apply, non-null
     * @return a stage completing with the outcome; an unknown id yields
     *         {@link WorkspaceOperationResult.Outcome#NOT_FOUND} rather than a failed stage
     */
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
        final WorkspaceStatus status = new WorkspaceStatus(
            WorkspaceStatus.Availability.UNAVAILABLE,
            Optional.empty(),
            List.of(),
            Optional.of("workspace.unavailable")
        );
        final WorkspaceOperationResult result = new WorkspaceOperationResult(
            WorkspaceOperationResult.Outcome.UNAVAILABLE,
            status,
            Optional.of("workspace.unavailable")
        );
        return new WorkspaceService() {
            @Override
            public CompletionStage<WorkspaceStatus> current() {
                return CompletableFuture.completedFuture(status);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
                Objects.requireNonNull(workspaceId, "workspaceId");
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> updateDefault() {
                return CompletableFuture.completedFuture(result);
            }

            @Override
            public CompletionStage<WorkspaceOperationResult> resetToDefault() {
                return CompletableFuture.completedFuture(result);
            }
        };
    }
}
