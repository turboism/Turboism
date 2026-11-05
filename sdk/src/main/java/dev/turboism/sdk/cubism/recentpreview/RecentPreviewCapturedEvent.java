package dev.turboism.sdk.cubism.recentpreview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.ui.PanelView;
import java.util.Objects;

/**
 * Public event published by the official recent-preview plugin after a thumbnail
 * capture is committed to its cache.
 *
 * <p>Consumer plugins declare a required or optional dependency on
 * {@code dev.turboism.plugin.recent-preview}, import the provider-owned
 * {@code thumbnail.captured} event contract, and subscribe through
 * {@link dev.turboism.sdk.event.EventBus}. The payload carries the opaque recent-file
 * id, the display name, and the captured PNG as a {@link ScreenshotImage}; calling
 * {@link #thumbnail()} returns an immutable {@link PanelView} ready for a panel or
 * popup contribution.</p>
 *
 * <p>The event is a live observation: it is not replayed to a subscriber that starts
 * after a capture. Consumers that need the existing cache on demand should keep their
 * own copy from received events or request a new capture through the host screenshot
 * service.</p>
 */
@CubismEditor
public record RecentPreviewCapturedEvent(RecentFileId id, String displayName, ScreenshotImage image)
        implements TurboismEvent {

    /** Logical popup display bounds used by the official plugin's thumbnail part. */
    private static final int DISPLAY_MAX = 150;

    public RecentPreviewCapturedEvent {
        id = Objects.requireNonNull(id, "id");
        displayName = Objects.requireNonNull(displayName, "displayName");
        if (displayName.isBlank()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        image = Objects.requireNonNull(image, "image");
    }

    /**
     * Returns a high-resolution image part displayed within the established popup bounds.
     * The returned view keeps the logical 150x150 layout while retaining the captured
     * pixels as a HiDPI variant.
     */
    public PanelView thumbnail() {
        return PanelView.scaledImage(image.png(), displayName, DISPLAY_MAX, DISPLAY_MAX);
    }
}
