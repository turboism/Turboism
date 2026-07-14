package dev.turboism.sdk.cubism;

import java.util.List;

public record WorkspaceSnapshot(
    String workspaceId,
    String displayName,
    String rootRelativePath,
    List<String> recentProjectIds
) {
    public WorkspaceSnapshot(
        final String workspaceId,
        final String rootRelativePath,
        final List<String> recentProjectIds
    ) {
        this(workspaceId, workspaceId, rootRelativePath, recentProjectIds);
    }

    public WorkspaceSnapshot {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be null or blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be null or blank");
        }
        if (rootRelativePath == null || rootRelativePath.isBlank() || rootRelativePath.startsWith("/") || rootRelativePath.contains("..")) {
            throw new IllegalArgumentException("rootRelativePath must be relative and must not contain parent segments");
        }
        recentProjectIds = List.copyOf(recentProjectIds);
    }
}
