package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureTargetUnavailableException;

import javax.imageio.ImageIO;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Adapter seam for bounded preview capture of recent project files. */
public interface ScreenshotCaptureAdapter {

    /**
     * Captures a bounded preview image for one recent project file.
     *
     * <p>The connected adapter additionally verifies that the returned result matches the
     * requested id, stays inside the requested bounds, and carries a decodable PNG;
     * violations fail the stage rather than returning a corrupt result.</p>
     *
     * @param request the capture request, non-null
     * @return a stage completing with the capture, or failing with
     *         {@link ScreenshotCaptureTargetUnavailableException} when the requested target
     *         no longer matches the host state, or another failure
     */
    CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);

    /**
     * An adapter for when no capture pipeline is attached.
     *
     * @return a host-free adapter whose captures always complete exceptionally with
     *         {@link UnsupportedOperationException}
     */
    static ScreenshotCaptureAdapter safeMode() {
        return request -> CompletableFuture.failedStage(
            new UnsupportedOperationException("screenshot capture is not available")
        );
    }

    /**
     * An adapter that captures through the given host operations and validates the result.
     *
     * @param host the live host operations, non-null
     * @return an adapter bound to that host
     * @throws NullPointerException if {@code host} is null
     */
    static ScreenshotCaptureAdapter connected(final HostOperations host) {
        Objects.requireNonNull(host, "host");
        return request -> host.capture(Objects.requireNonNull(request, "request")).thenApply(result -> {
            if (!request.id().equals(result.id())) {
                throw new ScreenshotCaptureTargetUnavailableException();
            }
            final var image = result.image();
            if (image.width() > request.maxWidth() || image.height() > request.maxHeight()) {
                throw new IllegalStateException("screenshot exceeds requested bounds");
            }
            try {
                final BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(image.png()));
                if (decoded == null || decoded.getWidth() != image.width() || decoded.getHeight() != image.height()) {
                    throw new IllegalStateException("screenshot is not a readable PNG");
                }
            } catch (IOException failure) {
                throw new IllegalStateException("screenshot is not a readable PNG", failure);
            }
            return result;
        });
    }

    /** The raw host call surface the connected adapter delegates to. */
    @FunctionalInterface
    interface HostOperations {
        /**
         * @param request the capture request
         * @return a stage completing with the raw host capture result
         */
        CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);
    }
}
