package dev.turboism.ui.workspace.layout;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;

import javax.swing.SwingUtilities;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Plugin-scoped workspace layout service. Permission checks run on the caller thread (never on
 * the AWT EDT, keeping audit work off the UI thread); the EDT is used only for the
 * execution-time liveness guard and the coordinator host read. {@link #close()} fences the EDT
 * so that after it returns no service-owned host call remains. Once closed, every operation
 * returns typed UNAVAILABLE without touching the permission checker or the provider.
 */
public final class RuntimeWorkspaceLayoutService implements WorkspaceLayoutService, AutoCloseable {

    private static final WorkspaceLayoutSnapshot UNAVAILABLE_SNAPSHOT = new WorkspaceLayoutSnapshot(
        WorkspaceLayoutSnapshot.Availability.UNAVAILABLE,
        Optional.empty(),
        Optional.of("workspace.layout.unavailable")
    );

    private final PermissionChecker permissionChecker;
    private final WorkspaceLayoutCoordinator coordinator;
    private volatile boolean closed;

    public RuntimeWorkspaceLayoutService(
        final PermissionChecker permissionChecker,
        final WorkspaceLayoutCoordinator coordinator
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public CompletionStage<WorkspaceLayoutSnapshot> current() {
        if (closed) {
            return CompletableFuture.completedFuture(UNAVAILABLE_SNAPSHOT);
        }
        permissionChecker.check(CubismFacadeImpl.PROJECT_READ_PERMISSION, "ui.workspace-layout.current");
        return CompletableFuture.completedFuture(admitOnEdt(() -> {
            if (closed) {
                return UNAVAILABLE_SNAPSHOT;
            }
            return coordinator.current();
        }));
    }

    /**
     * Marks the service closed and waits for the EDT to drain every already-queued service
     * operation (which observes the closed flag) before returning. The fence reuses
     * {@link WorkspaceLayoutCoordinator#dispatchOnEdt}, which preserves interruption and
     * reports fence failure instead of returning while quiescence is unproven.
     */
    @Override
    public void close() {
        closed = true;
        if (!SwingUtilities.isEventDispatchThread()) {
            WorkspaceLayoutCoordinator.dispatchOnEdt(() -> null);
        }
    }

    private static <T> T admitOnEdt(final java.util.function.Supplier<T> task) {
        return WorkspaceLayoutCoordinator.dispatchOnEdt(task::get);
    }
}
