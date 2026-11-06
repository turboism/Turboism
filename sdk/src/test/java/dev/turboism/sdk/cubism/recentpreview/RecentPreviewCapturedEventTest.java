package dev.turboism.sdk.cubism.recentpreview;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.ui.PanelView;
import org.junit.jupiter.api.Test;

class RecentPreviewCapturedEventTest {

    private static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void carriesTheOpaqueIdDisplayNameAndDefensiveImage() {
        final RecentFileId id = new RecentFileId("recent-1");
        final ScreenshotImage image = new ScreenshotImage(1, 1, PNG);
        final RecentPreviewCapturedEvent event = new RecentPreviewCapturedEvent(id, "model.cmo3", image);

        assertEquals(id, event.id());
        assertEquals("model.cmo3", event.displayName());
        assertEquals(image, event.image());
        assertArrayEquals(PNG, event.image().png());

        final PanelView.ScaledImage thumbnail = assertInstanceOf(PanelView.ScaledImage.class, event.thumbnail());
        assertEquals("model.cmo3", thumbnail.altText());
        assertEquals(150, thumbnail.maxWidth());
        assertEquals(150, thumbnail.maxHeight());
        assertArrayEquals(PNG, thumbnail.pngBytes());
    }

    @Test
    void validatesIdentityAndDisplayName() {
        final RecentFileId id = new RecentFileId("recent-1");
        final ScreenshotImage image = new ScreenshotImage(1, 1, PNG);
        assertThrows(NullPointerException.class, () -> new RecentPreviewCapturedEvent(null, "model.cmo3", image));
        assertThrows(NullPointerException.class, () -> new RecentPreviewCapturedEvent(id, null, image));
        assertThrows(IllegalArgumentException.class, () -> new RecentPreviewCapturedEvent(id, " ", image));
        assertThrows(NullPointerException.class, () -> new RecentPreviewCapturedEvent(id, "model.cmo3", null));
    }
}
