package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@PreviewApi
public interface WorkspaceService {

    CompletionStage<WorkspaceStatus> current();

    CompletionStage<WorkspaceOperationResult> switchTo(WorkspaceId workspaceId);

    CompletionStage<WorkspaceOperationResult> updateDefault();

    CompletionStage<WorkspaceOperationResult> resetToDefault();

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
