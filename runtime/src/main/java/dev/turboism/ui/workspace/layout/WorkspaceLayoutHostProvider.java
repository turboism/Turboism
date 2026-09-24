package dev.turboism.ui.workspace.layout;

import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutSnapshot;

/** Version-neutral host read for the current workspace dock layout. */
public interface WorkspaceLayoutHostProvider {

    /**
     * @return the host's current dock layout snapshot
     */
    WorkspaceLayoutSnapshot readLayout();
}
