package dev.turboism.sdk.hostread;

import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;

import java.util.Objects;
import java.util.Optional;

public record ProjectWorkspaceSnapshot(
    Optional<ProjectSnapshot> project,
    Optional<WorkspaceSnapshot> workspace
) implements AsyncHostReadValue {
    public ProjectWorkspaceSnapshot {
        project = Objects.requireNonNull(project, "project");
        workspace = Objects.requireNonNull(workspace, "workspace");
    }
}
