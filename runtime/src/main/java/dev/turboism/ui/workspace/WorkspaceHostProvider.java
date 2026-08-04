package dev.turboism.ui.workspace;

import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;

public interface WorkspaceHostProvider {
    WorkspaceStatus readStatus();
    WorkspaceOperationResult.Outcome switchTo(WorkspaceId workspaceId);
    WorkspaceOperationResult.Outcome updateDefault();
    WorkspaceOperationResult.Outcome resetToDefault();
}
