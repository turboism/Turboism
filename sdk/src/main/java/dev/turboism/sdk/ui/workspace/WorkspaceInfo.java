package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

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
