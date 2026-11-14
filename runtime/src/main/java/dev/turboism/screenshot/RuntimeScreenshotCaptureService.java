package dev.turboism.screenshot;

import dev.turboism.adapter.cubism.ScreenshotCaptureAdapter;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.CubismRenderedPixels;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureService;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureTargetUnavailableException;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

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

    /** Diagnostic component shared with recent-preview logs. */
    private static final String DIAGNOSTIC_COMPONENT = "recent-preview";

    private final ScreenshotCaptureAdapter adapter;
    private final PermissionChecker permissionChecker;
    private final Supplier<CubismModelAccess> modelAccess;
    private final Supplier<Optional<RecentFileId>> currentFileId;
    private final ExecutorService encoder;

    public RuntimeScreenshotCaptureService(
            final ScreenshotCaptureAdapter adapter, final PermissionChecker permissionChecker) {
        this(adapter, permissionChecker, CubismModelAccess::unavailable, null);
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
        this(adapter, permissionChecker, modelAccess, null);
    }

    /**
     * Creates the service with a native model render source and active-file target guard.
     *
     * @param adapter the component capture pipeline used when the native render is unavailable
     * @param permissionChecker the caller's permission gate
     * @param modelAccess resolves the session model access; it is a supplier because the verified
     *     session binds its model access after this service is composed
     * @param currentFileId supplies the active project's recent-file id, or {@code null} when no
     *     target guard is bound
     */
    public RuntimeScreenshotCaptureService(
            final ScreenshotCaptureAdapter adapter,
            final PermissionChecker permissionChecker,
            final Supplier<CubismModelAccess> modelAccess,
            final Supplier<Optional<RecentFileId>> currentFileId) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
        this.currentFileId = currentFileId;
        this.encoder = dev.turboism.runtime.exec.RuntimeThreads.serialLane(
                dev.turboism.runtime.exec.RuntimeThreads.daemonThreadsWithFixedName("turboism-preview-encode"));
    }

    @Override
    public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest request) {
        permissionChecker.check(PERMISSION, "cubism.screenshot.capture");
        final ScreenshotCaptureRequest target = Objects.requireNonNull(request, "request");
        final CompletableFuture<ScreenshotCaptureResult> result = new CompletableFuture<>();
        final Runnable task = () -> captureOnEdt(target, result);
        // Dispatch asynchronously to the Swing EDT so scheduler worker threads never block on
        // EdtDispatch and trip task-budget timeouts during model loading or native rendering.
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
        return result;
    }

    private void captureOnEdt(
            final ScreenshotCaptureRequest target, final CompletableFuture<ScreenshotCaptureResult> result) {
        try {
            if (!matchesActiveTarget(target.id())) {
                result.completeExceptionally(new ScreenshotCaptureTargetUnavailableException());
                return;
            }
            if (tryCaptureNativeOnEdt(target, result)) {
                return;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnoseFallback("native-capture:unexpected " + failure.getClass().getSimpleName());
        }
        forwardFallback(target, result);
    }

    private boolean matchesActiveTarget(final RecentFileId requestedId) {
        if (currentFileId == null) {
            return true;
        }
        final Optional<RecentFileId> activeId;
        try {
            activeId = currentFileId.get();
        } catch (RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnoseFallback(
                    "native-capture:target-check-failed " + failure.getClass().getSimpleName());
            return true;
        }
        if (activeId == null) {
            return true;
        }
        return activeId.isPresent() && activeId.get().equals(requestedId);
    }

    /**
     * Renders the active model natively on the EDT and schedules PNG encoding off-thread.
     *
     * @return {@code true} when native pixels were captured and queued for encoding, or
     *     {@code false} when the component pipeline must run as a fallback
     */
    private boolean tryCaptureNativeOnEdt(
            final ScreenshotCaptureRequest request, final CompletableFuture<ScreenshotCaptureResult> result) {
        final CubismModelAccess access;
        try {
            access = modelAccess.get();
        } catch (RuntimeException unavailable) {
            FatalErrors.rethrowIfFatal(unavailable);
            diagnoseFallback("native-capture:model-access-error "
                    + unavailable.getClass().getSimpleName());
            return false;
        }
        if (access == null || !access.isAvailable()) {
            diagnoseFallback("native-capture:model-access-unavailable");
            return false;
        }
        final CubismRenderedPixels pixels;
        try {
            pixels = access.active().renderPixels(request.maxWidth(), request.maxHeight());
        } catch (UnsupportedOperationException | IllegalStateException unavailable) {
            diagnoseFallback("native-capture:render-unavailable "
                    + unavailable.getClass().getSimpleName()
                    + ": "
                    + unavailable.getMessage());
            return false;
        } catch (RuntimeException failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnoseFallback(
                    "native-capture:render-failed " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return false;
        }
        if (pixels == null || pixels.width() < 1 || pixels.height() < 1 || isBlank(pixels)) {
            diagnoseFallback("native-capture:render-blank");
            return false;
        }
        final BufferedImage image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixels.width(), pixels.height(), pixels.argb(), 0, pixels.width());
        encoder.execute(() -> encodePng(request, image, result));
        return true;
    }

    private static void encodePng(
            final ScreenshotCaptureRequest request,
            final BufferedImage image,
            final CompletableFuture<ScreenshotCaptureResult> result) {
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
        } finally {
            image.flush();
        }
    }

    private void forwardFallback(
            final ScreenshotCaptureRequest target, final CompletableFuture<ScreenshotCaptureResult> result) {
        try {
            adapter.capture(target).whenComplete((captured, failure) -> {
                if (failure != null) {
                    result.completeExceptionally(failure);
                } else {
                    result.complete(captured);
                }
            });
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            result.completeExceptionally(failure);
        }
    }

    private static void diagnoseFallback(final String message) {
        try {
            RuntimeDiagnostics.warn(DIAGNOSTIC_COMPONENT, message);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
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
