package dev.turboism.sdk.cubism.screenshot;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;

import java.util.Objects;

/** Result of one bounded preview capture; the id always matches the request. */
public record ScreenshotCaptureResult(RecentFileId id, ScreenshotImage image) {
    public ScreenshotCaptureResult {
        id = Objects.requireNonNull(id, "id");
        image = Objects.requireNonNull(image, "image");
    }
}
