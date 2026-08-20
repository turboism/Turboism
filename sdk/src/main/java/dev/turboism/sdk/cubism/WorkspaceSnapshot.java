package dev.turboism.sdk.cubism;

import java.util.List;

/**
 * Immutable snapshot of a workspace: a named root directory under which projects live.
 *
 * <p>{@code rootRelativePath} is validated to be a contained relative path — no leading slash and
 * no {@code ..} segment — so a snapshot can never be used to address a location outside the host's
 * workspace root.</p>
 *
 * @param workspaceId stable identifier of the workspace; must not be null or blank
 * @param displayName human-readable label; defaults to {@code workspaceId} via the three-argument
 *     constructor, and must not be null or blank
 * @param rootRelativePath workspace root, relative to the host root; must not be absolute or
 *     contain parent segments
 * @param recentProjectIds unmodifiable copy of recently opened project identifiers, most-recent
 *     ordering as supplied by the host
 * @throws IllegalArgumentException if any text component is null or blank, or
 *     {@code rootRelativePath} escapes the host root
 * @throws NullPointerException if {@code recentProjectIds} is null
 */
public record WorkspaceSnapshot(
    String workspaceId,
    String displayName,
    String rootRelativePath,
    List<String> recentProjectIds
) {
    /** Convenience constructor that uses the workspace identifier as its display name. */
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
