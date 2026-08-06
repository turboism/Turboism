package dev.turboism.sdk.cubism.screenshot;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;

import java.util.Objects;

/**
 * Bounded preview capture request. Bounds are capped at 150×150 (the preview popup
 * contract); the captured image is scaled to fit both bounds while preserving aspect.
 */
@PreviewApi
public record ScreenshotCaptureRequest(RecentFileId id, int maxWidth, int maxHeight) {
    private static final int MAX_DIMENSION = 150;

    public ScreenshotCaptureRequest {
        id = Objects.requireNonNull(id, "id");
        requireDimension(maxWidth, "maxWidth");
        requireDimension(maxHeight, "maxHeight");
    }

    private static void requireDimension(final int value, final String name) {
        if (value < 1 || value > MAX_DIMENSION) {
            throw new IllegalArgumentException(name + " must be between 1 and " + MAX_DIMENSION);
        }
    }
}
