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

    /**
     * Resolves the current workspace dock layout, completing with an immutable snapshot.
     *
     * <p>Failures are reported as a typed {@code UNAVAILABLE} snapshot, not as an exceptional
     * completion.</p>
     */
    CompletionStage<WorkspaceLayoutSnapshot> current();

    /** Returns a fail-closed service that always resolves a {@code UNAVAILABLE} snapshot. */
    static WorkspaceLayoutService unavailable() {
        final WorkspaceLayoutSnapshot snapshot = new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            Optional.empty(),
            Optional.of("workspace.layout.unavailable")
        );
        return () -> CompletableFuture.completedFuture(snapshot);
    }
}
