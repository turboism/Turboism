package dev.turboism.screenshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

final class RuntimeScreenshotCaptureServiceTest {
    @Test
    void delegatesToConnectedRuntimeAdapter() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final ScreenshotCaptureResult result =
                new ScreenshotCaptureResult(request.id(), new ScreenshotImage(1, 1, png()));
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(result)),
                PermissionChecker.allowAll());

        assertEquals(result, service.capture(request).toCompletableFuture().join());
    }

    @Test
    void rejectsCaptureLargerThanRequestedBounds() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(
                        new ScreenshotCaptureResult(request.id(), new ScreenshotImage(151, 100, png(151, 100))))),
                PermissionChecker.allowAll());

        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.capture(request).toCompletableFuture().join());
    }

    @Test
    void rejectsResultForAnotherTarget() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(
                        new ScreenshotCaptureResult(new RecentFileId("other"), new ScreenshotImage(1, 1, png())))),
                PermissionChecker.allowAll());

        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.capture(request).toCompletableFuture().join());
    }

    @Test
    void safeModeFailsClosed() {
        final RuntimeScreenshotCaptureService service =
                new RuntimeScreenshotCaptureService(ScreenshotCaptureAdapter.safeMode(), PermissionChecker.allowAll());
        assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> service.capture(new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150))
                        .toCompletableFuture()
                        .join());
    }

    @Test
    void reportsUnavailableForSafeModeAdapter() {
        assertFalse(
                new RuntimeScreenshotCaptureService(ScreenshotCaptureAdapter.safeMode(), PermissionChecker.allowAll())
                        .isAvailable());
    }

    @Test
    void reportsAvailableForConnectedAdapter() {
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.failedStage(
                        new UnsupportedOperationException("capture refused in this test"))),
                PermissionChecker.allowAll());
        assertTrue(service.isAvailable());
    }

    @Test
    void aThrowingAvailabilityProbeReportsUnavailableInsteadOfThrowing() {
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                new ScreenshotCaptureAdapter() {
                    @Override
                    public java.util.concurrent.CompletionStage<ScreenshotCaptureResult> capture(
                            final ScreenshotCaptureRequest request) {
                        return CompletableFuture.failedStage(new UnsupportedOperationException("no host"));
                    }

                    @Override
                    public boolean available() {
                        // Mirrors DynamicRuntimeHostAdapters.call: an outermost
                        // adapter call may run session teardown on this thread.
                        throw new IllegalStateException("host session teardown on the calling thread");
                    }
                },
                PermissionChecker.allowAll());
        assertFalse(service.isAvailable(), "a throwing probe is an unavailable host, never an escaping failure");
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
                });

        assertThrows(
                dev.turboism.sdk.permission.CubismPermissionException.class,
                () -> service.capture(new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150)));
        assertEquals(0, calls[0]);
    }

    @Test
    void usesTheViewportReadPermissionId() {
        assertEquals(
                dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_VIEWPORT_READ,
                RuntimeScreenshotCaptureService.PERMISSION);
    }

    /**
     * The native render is the primary source: it draws only the model, so a real host no longer
     * publishes a toolbar-covered or blank GL readback. The component pipeline must not run when
     * the native render produced pixels.
     */
    @Test
    void prefersTheNativeModelRenderOverTheComponentPipeline() {
        final int[] adapterCalls = {0};
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(request -> {
                    adapterCalls[0]++;
                    return CompletableFuture.failedStage(new IllegalStateException("must not run"));
                }),
                PermissionChecker.allowAll(),
                () -> modelAccessReturning(pixels(4, 3)));

        final ScreenshotCaptureResult result = service.capture(
                        new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150))
                .toCompletableFuture()
                .join();

        assertEquals(0, adapterCalls[0], "the component pipeline must not run after a native render");
        assertEquals(4, result.image().width());
        assertEquals(3, result.image().height());
    }

    @Test
    void fallsBackToTheComponentPipelineWhenNoModelIsOpen() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final ScreenshotCaptureResult fallback =
                new ScreenshotCaptureResult(request.id(), new ScreenshotImage(1, 1, png()));
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(fallback)),
                PermissionChecker.allowAll(),
                () -> modelAccessReturning(pixels(0, 0)));

        assertEquals(fallback, service.capture(request).toCompletableFuture().join());
    }

    @Test
    void fallsBackToTheComponentPipelineWhenTheNativeRenderIsBlank() {
        final ScreenshotCaptureRequest request = new ScreenshotCaptureRequest(new RecentFileId("one"), 150, 150);
        final ScreenshotCaptureResult fallback =
                new ScreenshotCaptureResult(request.id(), new ScreenshotImage(1, 1, png()));
        final RuntimeScreenshotCaptureService service = new RuntimeScreenshotCaptureService(
                ScreenshotCaptureAdapter.connected(ignored -> CompletableFuture.completedStage(fallback)),
                PermissionChecker.allowAll(),
                () -> modelAccessReturning(new dev.turboism.sdk.cubism.model.CubismRenderedPixels(
                        2, 2, new int[] {0xFF000000, 0xFF000000, 0xFF000000, 0xFF000000})));

        assertEquals(fallback, service.capture(request).toCompletableFuture().join());
    }

    /**
     * Builds a model access whose render returns the given raster; a zero-sized raster models a
     * host that cannot render, which must fall through instead of failing the capture.
     */
    private static dev.turboism.sdk.cubism.model.CubismModelAccess modelAccessReturning(
            final dev.turboism.sdk.cubism.model.CubismRenderedPixels pixels) {
        final dev.turboism.sdk.cubism.model.CubismModel model =
                (dev.turboism.sdk.cubism.model.CubismModel) java.lang.reflect.Proxy.newProxyInstance(
                        RuntimeScreenshotCaptureServiceTest.class.getClassLoader(),
                        new Class<?>[] {dev.turboism.sdk.cubism.model.CubismModel.class},
                        (proxy, method, args) -> {
                            if ("renderPixels".equals(method.getName())) {
                                if (pixels.width() < 1) {
                                    throw new UnsupportedOperationException("native render is unavailable");
                                }
                                return pixels;
                            }
                            return dev.turboism.test.Stubs.defaultValue(method.getReturnType());
                        });
        return new dev.turboism.sdk.cubism.model.CubismModelAccess() {
            @Override
            public dev.turboism.sdk.cubism.model.CubismModel active() {
                return model;
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.CubismRenderedPixels pixels(final int width, final int height) {
        if (width < 1 || height < 1) {
            return new dev.turboism.sdk.cubism.model.CubismRenderedPixels(1, 1, new int[] {0});
        }
        final int[] argb = new int[width * height];
        for (int index = 0; index < argb.length; index++) {
            argb[index] = 0xFF000000 | (index * 977);
        }
        return new dev.turboism.sdk.cubism.model.CubismRenderedPixels(width, height, argb);
    }

    private static byte[] png() {
        return java.util.Base64.getDecoder()
                .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
    }

    private static byte[] png(final int width, final int height) {
        try {
            final var output = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(
                    new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                    "png",
                    output);
            return output.toByteArray();
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
