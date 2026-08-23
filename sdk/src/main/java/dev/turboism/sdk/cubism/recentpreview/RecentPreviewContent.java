package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.ui.PanelView;

import java.util.Objects;

/** Renderer-produced content for the recent-file hover popup of one project file. */
public record RecentPreviewContent(RecentFileId id, PanelView view) {
    public RecentPreviewContent {
        id = Objects.requireNonNull(id, "id");
        view = Objects.requireNonNull(view, "view");
    }
}
