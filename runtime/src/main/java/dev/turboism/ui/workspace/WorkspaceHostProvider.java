package dev.turboism.ui.workspace;

import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

/**
 * Version-specific access to the Editor's workspace controls. One
 * implementation exists per admitted Cubism version; the caller does not
 * choose between them directly.
 *
 * <p>Implementations are always invoked on the AWT event dispatch thread by
 * {@link WorkspaceCoordinator}, so they need no synchronization of their
 * own, and they may throw: the coordinator converts failures into a failed
 * result.</p>
 */
public interface WorkspaceHostProvider {
    WorkspaceStatus readStatus();
    WorkspaceOperationResult.Outcome switchTo(WorkspaceId workspaceId);
    WorkspaceOperationResult.Outcome updateDefault();
    WorkspaceOperationResult.Outcome resetToDefault();
}
