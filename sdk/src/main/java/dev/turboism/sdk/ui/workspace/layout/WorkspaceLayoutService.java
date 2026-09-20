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

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static WorkspaceLayoutService unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements WorkspaceLayoutService {
        INSTANCE;

        private static final WorkspaceLayoutSnapshot SNAPSHOT = new WorkspaceLayoutSnapshot(
            WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
            Optional.empty(),
            Optional.of("workspace.layout.unavailable")
        );

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public CompletionStage<WorkspaceLayoutSnapshot> current() {
            return CompletableFuture.completedFuture(SNAPSHOT);
        }
    }
}
