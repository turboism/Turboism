package dev.turboism.sdk.cubism;

import java.util.List;

public record WorkspaceSnapshot(
    String workspaceId,
    String rootRelativePath,
    List<String> recentProjectIds
) {
    public WorkspaceSnapshot {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be null or blank");
        }
        if (rootRelativePath == null || rootRelativePath.isBlank() || rootRelativePath.startsWith("/") || rootRelativePath.contains("..")) {
            throw new IllegalArgumentException("rootRelativePath must be relative and must not contain parent segments");
        }
        recentProjectIds = List.copyOf(recentProjectIds);
    }
}
