package dev.turboism.ui.workspace.layout;

import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;

/** Version-neutral host read for the current workspace dock layout. */
public interface WorkspaceLayoutHostProvider {

    WorkspaceLayoutSnapshot readLayout();
}
