package dev.turboism.sdk.hostread;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * The value delivered by a {@link AsyncHostReadIntent#PROJECT_WORKSPACE_SNAPSHOT} read: a
 * point-in-time view of the host's open project and workspace.
 *
 * <p>Both components are optional and independently so — the host may have a workspace open with
 * no project, or expose neither. An empty component means the host did not report that state at
 * the moment of the read, not that the read failed; failures are reported through
 * {@link AsyncHostReadResult} instead.
 *
 * @param project the open project's snapshot, empty when the host reported no project
 * @param workspace the workspace snapshot, empty when the host reported no workspace
 */
public record ProjectWorkspaceSnapshot(
    Optional<ProjectSnapshot> project,
    Optional<WorkspaceSnapshot> workspace
) implements AsyncHostReadValue {
    public ProjectWorkspaceSnapshot {
        project = Objects.requireNonNull(project, "project");
        workspace = Objects.requireNonNull(workspace, "workspace");
    }
}
