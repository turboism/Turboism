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

    CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);

    static ScreenshotCaptureAdapter safeMode() {
        return request -> CompletableFuture.failedStage(
            new UnsupportedOperationException("screenshot capture is not available")
        );
    }

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

    @FunctionalInterface
    interface HostOperations {
        CompletionStage<ScreenshotCaptureResult> capture(ScreenshotCaptureRequest request);
    }
}
