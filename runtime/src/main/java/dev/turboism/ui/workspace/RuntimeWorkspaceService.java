package dev.turboism.ui.workspace;

import dev.turboism.adapter.cubism.CubismFacadeImpl;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Plugin-scoped workspace service. Permission checks run on the caller thread (never on the AWT
 * EDT, keeping audit work off the UI thread); the EDT is used only for the execution-time liveness
 * guard and the coordinator host call. {@link #close()} fences the EDT so that after it returns no
 * service-owned host call remains. Once closed, every operation returns typed UNAVAILABLE without
 * touching the permission checker or the provider.
 */
public final class RuntimeWorkspaceService implements WorkspaceService, AutoCloseable {

    private static final WorkspaceStatus UNAVAILABLE_STATUS = new WorkspaceStatus(
        WorkspaceStatus.Availability.UNAVAILABLE,
        Optional.empty(),
        List.of(),
        Optional.of("workspace.unavailable")
    );
    private static final WorkspaceOperationResult UNAVAILABLE_RESULT = new WorkspaceOperationResult(
        WorkspaceOperationResult.Outcome.UNAVAILABLE,
        UNAVAILABLE_STATUS,
        Optional.of("workspace.unavailable")
    );

    private final PermissionChecker permissionChecker;
    private final WorkspaceCoordinator coordinator;
    private volatile boolean closed;

    public RuntimeWorkspaceService(
        final PermissionChecker permissionChecker,
        final WorkspaceCoordinator coordinator
    ) {
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override public CompletionStage<WorkspaceStatus> current() {
        if (closed) return CompletableFuture.completedFuture(UNAVAILABLE_STATUS);
        permissionChecker.check(CubismFacadeImpl.PROJECT_READ_PERMISSION, "ui.workspace.current");
        return CompletableFuture.completedFuture(admitOnEdt(() -> {
            if (closed) return UNAVAILABLE_STATUS;
            return coordinator.current();
        }));
    }

    @Override public CompletionStage<WorkspaceOperationResult> switchTo(final WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (closed) return CompletableFuture.completedFuture(UNAVAILABLE_RESULT);
        permissionChecker.check(PermissionIds.TURBOISM_HOST_UNSAFE, "ui.workspace.switch");
        return CompletableFuture.completedFuture(admitOnEdt(() -> {
            if (closed) return UNAVAILABLE_RESULT;
            return coordinator.switchTo(workspaceId);
        }));
    }

    @Override public CompletionStage<WorkspaceOperationResult> updateDefault() {
        if (closed) return CompletableFuture.completedFuture(UNAVAILABLE_RESULT);
        permissionChecker.check(PermissionIds.TURBOISM_HOST_UNSAFE, "ui.workspace.update-default");
        return CompletableFuture.completedFuture(admitOnEdt(() -> {
            if (closed) return UNAVAILABLE_RESULT;
            return coordinator.updateDefault();
        }));
    }

    @Override public CompletionStage<WorkspaceOperationResult> resetToDefault() {
        if (closed) return CompletableFuture.completedFuture(UNAVAILABLE_RESULT);
        permissionChecker.check(PermissionIds.TURBOISM_HOST_UNSAFE, "ui.workspace.reset-default");
        return CompletableFuture.completedFuture(admitOnEdt(() -> {
            if (closed) return UNAVAILABLE_RESULT;
            return coordinator.resetToDefault();
        }));
    }

    /**
     * Marks the service closed and waits for the EDT to drain every already-queued service
     * operation (which observes the closed flag) before returning. The fence reuses
     * {@link WorkspaceCoordinator#dispatchOnEdt}, which preserves interruption and reports fence
     * failure instead of returning while quiescence is unproven.
     */
    @Override
    public void close() {
        closed = true;
        if (!SwingUtilities.isEventDispatchThread()) {
            WorkspaceCoordinator.dispatchOnEdt(() -> null);
        }
    }

    private static <T> T admitOnEdt(final java.util.function.Supplier<T> task) {
        return WorkspaceCoordinator.dispatchOnEdt(task::get);
    }
}
