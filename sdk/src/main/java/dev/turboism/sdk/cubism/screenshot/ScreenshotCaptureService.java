package dev.turboism.sdk.cubism.screenshot;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous bounded preview capture for a recent project file. The capture runs on
 * the host UI thread; failures (unavailable surface, target changed, permission) are
 * reported on the returned stage and never escape the calling thread.
 */
public interface ScreenshotCaptureService {

    /**
     * Starts an asynchronous capture for {@code request}. The returned stage completes on the
     * host UI thread with the captured image, or exceptionally when the surface is
     * unavailable, the target changed, or permission is denied.
     */
    CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);

    /** Safe-mode instance: every capture completes exceptionally (fail closed). */
    static ScreenshotCaptureService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation returned by {@link #unavailable()}. */
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
