package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.event.TurboismEvent;
import java.util.Objects;

/**
 * Public event published by the official recent-preview plugin after a cached thumbnail is
 * deleted.
 *
 * <p>Consumer plugins declare a dependency on {@code dev.turboism.plugin.recent-preview},
 * import the {@code thumbnail.deleted} event contract, and subscribe through
 * {@link dev.turboism.sdk.event.EventBus}. A subscriber drops its own copy of the thumbnail for
 * {@link #id()}; the next capture republishes it through
 * {@link RecentPreviewCapturedEvent}.</p>
 */
@CubismEditor
public record RecentPreviewDeletedEvent(RecentFileId id) implements TurboismEvent {

    public RecentPreviewDeletedEvent {
        id = Objects.requireNonNull(id, "id");
    }
}
