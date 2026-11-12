package dev.turboism.screenshot;

import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Runtime {@link ScreenshotCaptureService}: permission-gated bounded preview capture.
 *
 * <p>The native offscreen model render is the primary source. It draws the Editor's current
 * parameter pose through the Editor's own renderer, so the image holds the model alone — no
 * toolbars, palettes, hover popup or stale GL back buffer. The component capture pipeline stays
 * as the fallback for a host whose renderer is unavailable, and for a capture requested while no
 * model is open.</p>
 */
public final class RuntimeScreenshotCaptureService implements ScreenshotCaptureService {
    public static final String PERMISSION = dev.turboism.sdk.permission.PermissionIds.TURBOISM_UI_VIEWPORT_READ;

    /** Upper bound on the dense-grid samples used by the blank-render check. */
    private static final int MAX_SOLIDITY_SAMPLES = 100_000;

    /** Fewer distinct sampled colours than this marks the render as blank. */
    private static final int MIN_DISTINCT_COLORS = 2;

    private final ScreenshotCaptureAdapter adapter;
    private final PermissionChecker permissionChecker;
    private final Supplier<CubismModelAccess> modelAccess;
    private final ExecutorService encoder;

    public RuntimeScreenshotCaptureService(
            final ScreenshotCaptureAdapter adapter, final PermissionChecker permissionChecker) {
        this(adapter, permissionChecker, CubismModelAccess::unavailable);
    }

    /**
     * Creates the service with a native model render source.
     *
     * @param adapter the component capture pipeline used when the native render is unavailable
     * @param permissionChecker the caller's permission gate
     * @param modelAccess resolves the session model access; it is a supplier because the verified
     *     session binds its model access after this service is composed
     */
    public RuntimeScreenshotCaptureService(
            final ScreenshotCaptureAdapter adapter,
            final PermissionChecker permissionChecker,
            final Supplier<CubismModelAccess> modelAccess) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
        this.encoder = dev.turboism.runtime.exec.RuntimeThreads.serialLane(
                dev.turboism.runtime.exec.RuntimeThreads.daemonThreadsWithFixedName("turboism-preview-encode"));
    }

    @Override
    public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest request) {
        permissionChecker.check(PERMISSION, "cubism.screenshot.capture");
        final ScreenshotCaptureRequest target = Objects.requireNonNull(request, "request");
        final CompletionStage<ScreenshotCaptureResult> nativeCapture = captureNative(target);
        if (nativeCapture != null) {
            return nativeCapture;
        }
        return adapter.capture(target);
    }

    /**
     * Renders the active model natively.
     *
     * @return the capture stage, or {@code null} when the native render cannot serve this request
     *     and the component pipeline must run instead
     */
    private CompletionStage<ScreenshotCaptureResult> captureNative(final ScreenshotCaptureRequest request) {
        final CubismModelAccess access;
        try {
            access = modelAccess.get();
        } catch (RuntimeException unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            return null;
        }
        if (access == null || !access.isAvailable()) {
            return null;
        }
        final CubismRenderedPixels pixels;
        try {
            pixels = access.active().renderPixels(request.maxWidth(), request.maxHeight());
        } catch (UnsupportedOperationException | IllegalStateException unavailable) {
            // No verified renderer, or no open model: the component pipeline still applies.
            return null;
        } catch (RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
        if (pixels == null || pixels.width() < 1 || pixels.height() < 1 || isBlank(pixels)) {
            return null;
        }
        final BufferedImage image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixels.width(), pixels.height(), pixels.argb(), 0, pixels.width());
        final CompletableFuture<ScreenshotCaptureResult> result = new CompletableFuture<>();
        encoder.execute(() -> {
            try {
                final ByteArrayOutputStream png = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", png)) {
                    result.completeExceptionally(new IllegalStateException("PNG writer is unavailable"));
                    return;
                }
                result.complete(new ScreenshotCaptureResult(
                        request.id(), new ScreenshotImage(image.getWidth(), image.getHeight(), png.toByteArray())));
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    /**
     * Whether the render sampled as effectively one colour. A blank render means the Editor
     * produced an empty surface, so the component pipeline is asked instead of publishing a
     * featureless thumbnail.
     */
    private static boolean isBlank(final CubismRenderedPixels pixels) {
        final int width = pixels.width();
        final int height = pixels.height();
        final int stride = Math.max(1, (int) Math.ceil(Math.sqrt((double) width * height / MAX_SOLIDITY_SAMPLES)));
        final java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                colors.add(pixels.argbAt(x, y));
                if (colors.size() >= MIN_DISTINCT_COLORS) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean isAvailable() {
        // A probe failure (for example host-session teardown running on this
        // thread) means the host is unavailable; the contract never throws.
        try {
            return adapter.available();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }
}
