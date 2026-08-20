package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/**
 * A workspace layout as it should be presented to the user: its key plus the label to show.
 *
 * <p>Both components are validated at construction; the display name is never blank, so callers
 * rendering a workspace picker never have to substitute a placeholder.
 *
 * @param id          the host key of the workspace, non-null
 * @param displayName the label shown to the user, non-null and non-blank
 */
@PreviewApi
public record WorkspaceInfo(WorkspaceId id, String displayName) {
    public WorkspaceInfo {
        id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
    }
}
