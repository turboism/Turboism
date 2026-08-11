package dev.turboism.sdk.cubism.screenshot;

import dev.turboism.sdk.PreviewApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous bounded preview capture for a recent project file. The capture runs on
 * the host UI thread; failures (unavailable surface, target changed, permission) are
 * reported on the returned stage and never escape the calling thread.
 */
@PreviewApi
public interface ScreenshotCaptureService {

    CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);

    /** Safe-mode instance: every capture completes exceptionally (fail closed). */
    static ScreenshotCaptureService unavailable() {
        return Unavailable.INSTANCE;
    }

    @PreviewApi
    enum Unavailable implements ScreenshotCaptureService {
        INSTANCE;

        @Override
        public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest request) {
            return CompletableFuture.failedStage(
                new UnsupportedOperationException("screenshot capture service is not available")
            );
        }
    }
}
