package dev.turboism.sdk.ui.workspace.layout;


import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Read-only query for the current workspace dock layout tree.
 *
 * <p>{@link #current()} resolves the whole host chain on every call and returns an immutable
 * snapshot; host read failures surface as typed {@code UNAVAILABLE} snapshots, but synchronous
 * admission failures such as permission denial may still be thrown before a stage is returned.
 * After the owning plugin scope is closed the service returns typed {@code UNAVAILABLE}
 * snapshots.</p>
 */
public interface WorkspaceLayoutService {

    /**
     * Resolves the current workspace dock layout, completing with an immutable snapshot.
     *
     * <p>Host read failures resolve as a typed {@code UNAVAILABLE} snapshot rather than an
     * exceptional completion; permission denial or a scheduling failure may still be thrown
     * synchronously before the stage is returned.</p>
     */
    CompletionStage<WorkspaceLayoutSnapshot> current();

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }


    /** Returns a fail-closed service that always resolves a {@code UNAVAILABLE} snapshot. */
    static WorkspaceLayoutService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Sentinel returned by {@link #unavailable()}: calls that report outcomes complete with the structured unavailability result; and queries report empty results. */
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
