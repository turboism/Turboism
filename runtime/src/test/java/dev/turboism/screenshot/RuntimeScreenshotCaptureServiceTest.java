package dev.turboism.screenshot;

import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RuntimeScreenshotCaptureServiceTest {
    @Test
    void delegatesToConnectedRuntimeAdapter() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final ScreenshotCaptureResult result = new ScreenshotCaptureResult(
            request.id(), new ScreenshotImage(1, 1, png())
        );
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
            ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(result)),
            PermissionChecker.allowAll()
        );

        assertEquals(result, service.capture(request).toCompletableFuture().join());
    }

    @Test
    void rejectsCaptureLargerThanRequestedBounds() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
            ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(
                new ScreenshotCaptureResult(request.id(),
                    new ScreenshotImage(151, 100, png(151, 100)))
            )),
            PermissionChecker.allowAll()
        );

        assertThrows(java.util.concurrent.CompletionException.class,
            () -> service.capture(request).toCompletableFuture().join());
    }

    @Test
    void rejectsResultForAnotherTarget() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
            ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(
                new ScreenshotCaptureResult(new RecentFileId("other"), new ScreenshotImage(1, 1, png()))
            )),
            PermissionChecker.allowAll()
        );

        assertThrows(java.util.concurrent.CompletionException.class,
            () -> service.capture(request).toCompletableFuture().join());
    }

    @Test
    void safeModeFailsClosed() {
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
            ScreenshotCaptureAdapter.safeMode(), PermissionChecker.allowAll()
        );
        assertThrows(java.util.concurrent.CompletionException.class, () -> service.capture(
            new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150)
        ).toCompletableFuture().join());
    }

    @Test
    void checksViewportReadPermissionBeforeCallingAdapter() {
        final int[] calls = {0};
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
            ScreenshotCaptureAdapter.connected(request -> {
                calls[0]++;
                return CompletableFuture.failedStage(new IllegalStateException("must not run"));
            }),
            (permission, operation) -> {
                throw new dev.turboism.sdk.permission.CubismPermissionException("denied");
            }
        );

        assertThrows(dev.turboism.sdk.permission.CubismPermissionException.class, () -> service.capture(
            new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150)
        ));
        assertEquals(0, calls[0]);
    }

    @Test
    void usesTheViewportReadPermissionId() {
        assertEquals(
            dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_VIEWPORT_READ,
            RuntimeScreenshotCaptureService.PERMISSION
        );
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        );
    }

    private static byte[] png(final int width, final int height) {
        try {
            final var output = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                "png",
                output
            );
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
