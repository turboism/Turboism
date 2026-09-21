package dev.turboism.sdk.cubism.screenshot;


import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Asynchronous bounded preview capture for a recent project file. Implementations may
 * validate the call synchronously — permission checks and argument checks can throw before
 * a stage is returned; once returned, the stage's completion thread and failure modes are
 * implementation-defined.
 */
public interface ScreenshotCaptureService {

    /**
     * Starts a capture for {@code request}. Permission and argument validation may throw
     * synchronously; otherwise the host's capture stage is returned, completing with the
     * captured image or exceptionally (for example when the surface is unavailable or the
     * target changed).
     */
    CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /** Safe-mode instance: every call returns an already-failed stage (fail closed). */
    static ScreenshotCaptureService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements ScreenshotCaptureService {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override
        public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest request) {
            return CompletableFuture.failedStage(
                new UnsupportedOperationException("screenshot capture service is not available")
            );
        }
    }
}
