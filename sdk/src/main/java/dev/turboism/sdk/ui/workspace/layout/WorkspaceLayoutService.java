package dev.turboism.sdk.ui.workspace.layout;


import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Read-only query for the current workspace dock layout tree.
 *
 * <p>{@link #current()} resolves the whole host chain on every call and returns an immutable
 * snapshot; the service never throws host exceptions to the plugin. After the owning plugin
 * scope is closed the service returns typed {@code UNAVAILABLE} snapshots.</p>
 */
public interface WorkspaceLayoutService {

    CompletionStage<WorkspaceLayoutSnapshot> current();

    static WorkspaceLayoutService unavailable() {
        final WorkspaceLayoutSnapshot snapshot = new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            Optional.empty(),
            Optional.of("workspace.layout.unavailable")
        );
        return () -> CompletableFuture.completedFuture(snapshot);
    }
}
