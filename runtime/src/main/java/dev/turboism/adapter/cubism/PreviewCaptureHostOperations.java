package dev.turboism.adapter.cubism;

import dev.turboism.mapping.verification.RecentPreviewVerificationManifest;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureRequest;
import dev.turboism.sdk.cubism.screenshot.ScreenshotCaptureResult;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;

import javax.imageio.ImageIO;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Preview capture pipeline ported from the legacy {@code CubismPreviewCaptureService}/
 * {@code CubismRecentPreviewManager}: legacy component chain
 * (getCompletePack→getMainViewPanel / getCanvasCtrl / getMainFrame), component scoring,
 * overlay suppression, three-tier capture (offscreen paint → JOGL drawable readback →
 * Robot on-screen fallback), margin cropping, bounded scaling, capture debounce, and the
 * current-project match guard. Everything runs on the EDT and completes the returned
 * stage; failures never escape the caller's thread.
 *
 * <p>The {@code getCompletePack}/... chain and the JOGL readback are reflection-by-name
 * against the host UI classes reached through the verified app-controller root; every
 * failure fails closed to the next tier or to an exceptional completion.</p>
 */
public final class PreviewCaptureHostOperations implements ScreenshotCaptureAdapter.HostOperations {

    /** Longest allowed debounce window; mirrors the legacy RECENT_PREVIEW_CAPTURE_DEBOUNCE_MS. */
    static final long CAPTURE_DEBOUNCE_MS = 2500L;

    /**
     * Solidity health-check thresholds: a tier result with fewer distinct sampled
     * colors than {@link #MIN_DISTINCT_COLORS} over a dense grid, or whose sampled
     * luminance variance stays below {@link #MAX_LUMINANCE_VARIANCE}, is treated as
     * a failed tier (e.g. a GL readback returning a solid clear-color buffer) and the
     * pipeline falls through to the next tier. See {@link #isSolidContent(BufferedImage)}.
     */
    static final int MIN_DISTINCT_COLORS = 2;
    static final double MAX_LUMINANCE_VARIANCE = 25.0;

    /** Upper bound on the dense-grid samples used by the solidity check. */
    static final int MAX_SOLIDITY_SAMPLES = 100_000;

    /** Hides/restores the runtime's own recent-preview popup around a capture. */
    public interface PopupSuppression {
        void hide();

        void restore();
    }

    private final VerifiedMemberResolver panelResolver;
    private final VerifiedRecentFileListHostOperations files;
    private final PopupSuppression popupSuppression;

    /**
     * Fail-closed diagnostic sink; every capture failure branch reports a short reason.
     * Static because the capture tiers are static; a single host instance owns the
     * process. Wired to System.err for host verification (temporary).
     */
    private static volatile Consumer<String> diagnostics = reason -> { };

    private final Object debounceGate = new Object();
    private RecentFileId debouncedId;
    private long debouncedAtMillis;
    private ScreenshotImage debouncedImage;

    public PreviewCaptureHostOperations(
        final VerifiedMemberResolver panelResolver,
        final VerifiedRecentFileListHostOperations files,
        final PopupSuppression popupSuppression,
        final Consumer<String> diagnosticsSink
    ) {
        this.panelResolver = Objects.requireNonNull(panelResolver, "panelResolver");
        this.files = Objects.requireNonNull(files, "files");
        this.popupSuppression = Objects.requireNonNull(popupSuppression, "popupSuppression");
        PreviewCaptureHostOperations.diagnostics = Objects.requireNonNull(diagnosticsSink, "diagnosticsSink");
        RecentPreviewVerificationManifest.requireAuthorized(files.projectResolver(), panelResolver);
        RecentMenuChain.PANEL_ALIASES.forEach(panelResolver::verifiedSelector);
    }

    /** Default no-op diagnostics sink (tests). */
    public PreviewCaptureHostOperations(
        final VerifiedMemberResolver panelResolver,
        final VerifiedRecentFileListHostOperations files,
        final PopupSuppression popupSuppression
    ) {
        this(panelResolver, files, popupSuppression, reason -> { });
    }

    private static void diagnose(final String reason) {
        diagnostics.accept("capture-diag:" + reason);
    }

    private static String className(final Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    @Override
    public CompletionStage<ScreenshotCaptureResult> capture(final ScreenshotCaptureRequest request) {
        Objects.requireNonNull(request, "request");
        final CompletableFuture<ScreenshotCaptureResult> result = new CompletableFuture<>();
        final Runnable capture = () -> {
            try {
                result.complete(captureNow(request));
            } catch (Throwable failure) {
                diagnose("capture:failed " + failure.getClass().getName());
                result.completeExceptionally(failure);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) capture.run(); else SwingUtilities.invokeLater(capture);
        return result;
    }

    private ScreenshotCaptureResult captureNow(final ScreenshotCaptureRequest request) throws Exception {
        final Path current = RecentMenuChain.currentProjectPath(files.projectResolver());
        final Path expected = files.pathFor(request.id());
        if (current == null
            || (expected != null && !samePath(expected, current))
            || (expected == null && !VerifiedRecentFileListHostOperations.idFor(current).equals(request.id()))) {
            diagnose("captureNow:target-guard current-null=" + (current == null)
                + " expected-null=" + (expected == null));
            throw new IllegalStateException("screenshot target changed during capture");
        }
        final ScreenshotImage cached = debounced(request);
        if (cached != null) {
            return new ScreenshotCaptureResult(request.id(), cached);
        }
        final Component root = captureRoot();
        final Component target = resolveCaptureComponent();
        if (target == null && root == null) {
            diagnose("captureNow:no-surface target-null=" + (target == null) + " root-null=" + (root == null));
            throw new IllegalStateException("Cubism preview capture surface is unavailable");
        }
        final List<Object> suppressed = suppressOverlays(root);
        popupSuppression.hide();
        BufferedImage captured = null;
        String tier = "none";
        try {
            final CapturedImage fromTarget = target == null ? null : captureComponentImage(target);
            if (fromTarget != null) {
                captured = fromTarget.image();
                tier = fromTarget.tier();
            } else if (root != null) {
                final CapturedImage fromRoot = captureComponentImage(root);
                if (fromRoot != null) {
                    captured = fromRoot.image();
                    tier = fromRoot.tier();
                }
            }
        } finally {
            restoreOverlays(suppressed);
            popupSuppression.restore();
        }
        if (captured == null) {
            diagnose("captureNow:all-tiers-null target=" + className(target) + " root=" + className(root));
            throw new IllegalStateException("Cubism preview capture surface is unavailable");
        }
        if (isSolidContent(captured)) {
            // Every tier produced a solid/empty buffer; keep the best (first non-null)
            // result — an empty scene is a legitimate capture — but record the health line.
            diagnose("captureNow:solid-content tier=" + tier
                + " size=" + captured.getWidth() + "x" + captured.getHeight());
        }
        final BufferedImage scaled = scale(cropMargins(captured), request.maxWidth(), request.maxHeight());
        diagnose("captureNow:ok root=" + className(root)
            + " target=" + className(target)
            + " tier=" + tier
            + " pre-scale=" + captured.getWidth() + "x" + captured.getHeight()
            + " png=" + scaled.getWidth() + "x" + scaled.getHeight());
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(scaled, "png", png)) {
            diagnose("captureNow:png-writer-unavailable");
            throw new IllegalStateException("PNG writer is unavailable");
        }
        final ScreenshotImage image = new ScreenshotImage(scaled.getWidth(), scaled.getHeight(), png.toByteArray());
        rememberDebounced(request.id(), image);
        return new ScreenshotCaptureResult(request.id(), image);
    }

    /** One-entry debounce cache: identical requests within the legacy window reuse the last image. */
    private ScreenshotImage debounced(final ScreenshotCaptureRequest request) {
        synchronized (debounceGate) {
            if (debouncedId == null || !debouncedId.equals(request.id())) return null;
            if (System.currentTimeMillis() - debouncedAtMillis >= CAPTURE_DEBOUNCE_MS) return null;
            if (debouncedImage.width() > request.maxWidth() || debouncedImage.height() > request.maxHeight()) {
                return null;
            }
            return debouncedImage;
        }
    }

    private void rememberDebounced(final RecentFileId id, final ScreenshotImage image) {
        synchronized (debounceGate) {
            debouncedId = id;
            debouncedAtMillis = System.currentTimeMillis();
            debouncedImage = image;
        }
    }

    /**
     * Legacy capture component chain over the verified app-controller root:
     * getCompletePack→getMainViewPanel, getCanvasCtrl, getMainFrame (first non-null),
     * then component scoring; the chain result is used directly when scoring finds
     * nothing better. Null when the chain yields no Swing component (window-root
     * fallback is applied by the caller).
     */
    static Component resolveCaptureComponent(final VerifiedMemberResolver panelResolver) {
        final Object app = panelResolver.invokeStatic(RecentMenuChain.PANEL_APP_INSTANCE);
        final Object mainViewPanel = chainStep(chainStep(app, "getCompletePack", "getCompletePack"),
            "getMainViewPanel", "getCompletePack→getMainViewPanel");
        final Object canvasCtrl = chainStep(app, "getCanvasCtrl", "getCanvasCtrl");
        final Object mainFrame = chainStep(app, "getMainFrame", "getMainFrame");
        final Object raw = firstNonNull(new Object[]{mainViewPanel, canvasCtrl, mainFrame});
        if (raw == null) {
            diagnose("resolveCaptureComponent:chain all-null");
            return null;
        }
        final Component base = extractSwingComponent(raw);
        if (base == null) {
            diagnose("resolveCaptureComponent:not-swing class=" + className(raw));
            return null;
        }
        final Component best = findBestCaptureComponent(base);
        return best == null ? base : best;
    }

    /** One reflection step of the legacy capture chain; records null/exception class per step. */
    private static Object chainStep(final Object target, final String name, final String step) {
        if (target == null) {
            diagnose("resolveCaptureComponent:" + step + " skipped-target-null");
            return null;
        }
        try {
            final Object result = target.getClass().getMethod(name).invoke(target);
            if (result == null) {
                diagnose("resolveCaptureComponent:" + step + "→null");
            }
            return result;
        } catch (Throwable failure) {
            diagnose("resolveCaptureComponent:" + step + " " + failure.getClass().getName());
            return null;
        }
    }

    /** Largest visible, displayable, non-dialog Window; null when none qualifies (fail closed). */
    static Window selectCaptureWindow(final Window[] windows) {
        Window target = null;
        long largestArea = -1L;
        for (final Window window : windows) {
            if (window instanceof Dialog || !window.isDisplayable() || !window.isVisible()) continue;
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        return target;
    }

    static Component findBestCaptureComponent(final Component root) {
        if (root == null || !root.isShowing() || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        Component best = preferred(root) ? root : null;
        long score = score(best);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                final Component nested = findBestCaptureComponent(child);
                final long nestedScore = score(nested);
                if (nestedScore > score) {
                    best = nested;
                    score = nestedScore;
                }
            }
        }
        return best;
    }

    private static boolean preferred(final Component component) {
        if (component == null || !component.isShowing() || component.getWidth() < 180 || component.getHeight() < 120) {
            return false;
        }
        final String signature = signature(component);
        if (signature.contains("menu") || signature.contains("toolbar")
            || signature.contains("scrollbar") || signature.contains("splitpane")) {
            return false;
        }
        return signature.contains("glcanvas") || signature.contains("gljpanel") || signature.contains("jogamp")
            || signature.contains("canvas") || signature.contains("mainview")
            || signature.contains("viewpanel") || signature.contains("scene")
            || signature.contains("viewport") || dark(component);
    }

    private static long score(final Component component) {
        if (!preferred(component)) return 0;
        long score = (long) component.getWidth() * component.getHeight();
        final String signature = signature(component);
        if (signature.contains("glcanvas") || signature.contains("gljpanel") || signature.contains("jogamp")) {
            score += 5_000_000L;
        }
        if (signature.contains("canvas") || signature.contains("viewport")) {
            score += 2_500_000L;
        }
        if (dark(component)) {
            score += 1_500_000L;
        }
        if (component instanceof Container container) {
            score -= Math.min(container.getComponentCount(), 200) * 10_000L;
        }
        return score;
    }

    private static String signature(final Component component) {
        return (component.getClass().getName() + " " + Objects.toString(component.getName(), ""))
            .toLowerCase(Locale.ROOT);
    }

    private static boolean dark(final Component component) {
        final java.awt.Color color = component.getBackground();
        final int brightness = color == null ? -1 : color.getRed() + color.getGreen() + color.getBlue();
        return brightness >= 70 && brightness <= 430;
    }

    /**
     * Legacy three-tier capture: JOGL drawable readback → offscreen Swing paint →
     * Robot on-screen fallback. Null on any failure (fail closed); the returned
     * {@link CapturedImage} records which tier produced the image.
     */
    static CapturedImage captureComponentImage(final Component component) throws Exception {
        if (component == null || !component.isShowing()
            || component.getWidth() <= 0 || component.getHeight() <= 0) {
            diagnose("captureComponentImage:not-capturable class=" + className(component)
                + " showing=" + (component != null && component.isShowing())
                + " size=" + (component == null ? 0 : component.getWidth())
                + "x" + (component == null ? 0 : component.getHeight()));
            return null;
        }
        // JOGL readback and offscreen paint need no active window; only the Robot tier does,
        // and captureOnScreen already enforces its own active/focused check. A Window
        // component is its own ancestor (getWindowAncestor returns null for a top-level Window).
        final Window ancestor = component instanceof Window window
            ? window
            : SwingUtilities.getWindowAncestor(component);
        if (ancestor == null || !ancestor.isShowing()) {
            diagnose("captureComponentImage:ancestor-unavailable class=" + className(component)
                + " ancestor-null=" + (ancestor == null)
                + " ancestor-showing=" + (ancestor != null && ancestor.isShowing()));
            return null;
        }
        final String signature = signature(component);
        final boolean glSurface = signature.contains("glcanvas") || signature.contains("gljpanel")
            || signature.contains("jogamp");
        final BufferedImage jogl = glSurface ? captureJogl(component) : null;
        if (jogl != null && !isSolidContent(jogl)) return new CapturedImage(jogl, "jogl");
        diagnose("captureComponentImage:tier-jogl=" + (glSurface ? (jogl == null ? "failed" : "solid") : "skipped")
            + " class=" + className(component));
        final BufferedImage painted = paintToImage(component);
        if (painted != null && !isSolidContent(painted)) return new CapturedImage(painted, "paint");
        diagnose("captureComponentImage:tier-paint=" + (painted == null ? "failed" : "solid")
            + " class=" + className(component));
        try {
            final BufferedImage robot = captureOnScreen(component);
            if (robot != null && !isSolidContent(robot)) return new CapturedImage(robot, "robot");
            if (robot != null) {
                diagnose("captureComponentImage:tier-robot=solid class=" + className(component));
            }
            // Every tier produced a solid/empty buffer: still return the best (first
            // non-null) result; the caller records a captureNow:solid-content line.
            if (jogl != null) return new CapturedImage(jogl, "jogl");
            if (painted != null) return new CapturedImage(painted, "paint");
            return robot == null ? null : new CapturedImage(robot, "robot");
        } catch (Exception robotFailure) {
            diagnose("captureComponentImage:tier-robot " + robotFailure.getClass().getName()
                + " class=" + className(component));
            if (jogl != null) return new CapturedImage(jogl, "jogl");
            if (painted != null) return new CapturedImage(painted, "paint");
            throw robotFailure;
        }
    }

    /**
     * Health check for one tier result: an effectively solid or empty buffer (fewer
     * distinct sampled colors than {@link #MIN_DISTINCT_COLORS} over a dense grid, or
     * all sampled pixels within a small luminance variance) marks the tier as failed
     * so the pipeline falls through to the next tier. Blank images stay valid at the
     * end of the pipeline (an empty scene is a legitimate capture); the caller only
     * records the solid-content diagnostic. Null and zero-size images are solid.
     */
    static boolean isSolidContent(final BufferedImage image) {
        if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) return true;
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int stride = Math.max(1, (int) Math.ceil(
            Math.sqrt((double) width * height / MAX_SOLIDITY_SAMPLES)));
        final java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        long count = 0;
        double mean = 0;
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                final int argb = image.getRGB(x, y);
                colors.add(argb);
                mean += luminance(argb);
                count++;
            }
        }
        if (colors.size() < MIN_DISTINCT_COLORS) return true;
        mean /= count;
        double variance = 0;
        for (int y = 0; y < height; y += stride) {
            for (int x = 0; x < width; x += stride) {
                final double delta = luminance(image.getRGB(x, y)) - mean;
                variance += delta * delta;
            }
        }
        return variance / count < MAX_LUMINANCE_VARIANCE;
    }

    private static double luminance(final int argb) {
        return 0.299 * ((argb >> 16) & 0xFF) + 0.587 * ((argb >> 8) & 0xFF) + 0.114 * (argb & 0xFF);
    }

    /** Captured image plus the tier that produced it ({@code jogl|paint|robot}). */
    record CapturedImage(BufferedImage image, String tier) { }

    private static BufferedImage paintToImage(final Component component) {
        if (!(component instanceof JComponent swing)) return null;
        final BufferedImage image = new BufferedImage(
            component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_ARGB
        );
        final Graphics2D graphics = image.createGraphics();
        try {
            graphics.setClip(0, 0, image.getWidth(), image.getHeight());
            swing.printAll(graphics);
            return image;
        } catch (RuntimeException | Error failure) {
            diagnose("paintToImage:" + failure.getClass().getName());
            return null;
        } finally {
            graphics.dispose();
        }
    }

    /** Robot on-screen fallback: requires an active and focused window ancestor. */
    static BufferedImage captureOnScreen(final Component component) throws Exception {
        final Window ancestor = SwingUtilities.getWindowAncestor(component);
        if (ancestor != null && (!ancestor.isActive() || !ancestor.isFocused())) {
            diagnose("captureOnScreen:window-not-active-focused class=" + className(component)
                + " active=" + ancestor.isActive() + " focused=" + ancestor.isFocused());
            return null;
        }
        final Point location = component.getLocationOnScreen();
        final Rectangle bounds = new Rectangle(location.x, location.y, component.getWidth(), component.getHeight());
        final java.awt.GraphicsConfiguration configuration = component.getGraphicsConfiguration();
        final java.awt.GraphicsDevice device = configuration == null
            ? java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
            : configuration.getDevice();
        return new Robot(device).createScreenCapture(bounds);
    }

    /** JOGL GL readback inside the drawable context; null on any reflection/GL failure (fail closed). */
    private static BufferedImage captureDrawable(final Object drawable, final int width, final int height) {
        try {
            final ClassLoader loader = drawable.getClass().getClassLoader();
            final Class<?> drawableType = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
            final Class<?> runnableType = Class.forName("com.jogamp.opengl.GLRunnable", false, loader);
            final Class<?> listenerType = Class.forName("com.jogamp.opengl.GLEventListener", false, loader);
            if (!drawableType.isInstance(drawable)) {
                diagnose("captureDrawable:not-auto-drawable class=" + className(drawable));
                return null;
            }
            final Method add = drawableType.getMethod("addGLEventListener", listenerType);
            final Method remove = drawableType.getMethod("removeGLEventListener", listenerType);
            final Method display = drawableType.getMethod("display");
            final Method invoke = drawableType.getMethod("invoke", boolean.class, runnableType);
            final AtomicReference<BufferedImage> image = new AtomicReference<>();
            final Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
                final String name = method.getName();
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == args[0];
                if ("toString".equals(name)) return "turboism-capture-" + System.identityHashCode(proxy);
                if ("display".equals(name) && args != null && args.length == 1) {
                    image.set(readJogl(args[0], width, height));
                }
                return null;
            });
            final Object renderPass = Proxy.newProxyInstance(loader, new Class<?>[]{runnableType}, (proxy, method, args) -> {
                final String name = method.getName();
                if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                if ("equals".equals(name)) return proxy == args[0];
                if ("toString".equals(name)) return "turboism-render-" + System.identityHashCode(proxy);
                if ("run".equals(name) && args != null && args.length == 1) {
                    drainGlErrors(drawable);
                    display.invoke(drawable);
                    return Boolean.TRUE;
                }
                return null;
            });
            add.invoke(drawable, listener);
            try {
                for (int attempt = 0; attempt < 3 && image.get() == null; attempt++) {
                    try {
                        drainGlErrors(drawable);
                        display.invoke(drawable);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                    }
                    if (image.get() != null) break;
                    try {
                        invoke.invoke(drawable, true, renderPass);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                    }
                    if (image.get() == null) Thread.sleep(300L);
                }
                if (image.get() == null) {
                    diagnose("captureDrawable:readback-null-after-3 class=" + className(drawable));
                }
                return image.get();
            } finally {
                remove.invoke(drawable, listener);
            }
        } catch (Throwable failure) {
            diagnose("captureDrawable:" + failure.getClass().getName());
            return null;
        }
    }

    private static BufferedImage captureJogl(final Component component) {
        try {
            final Object shared = sharedDrawable(component);
            if (shared != null) {
                final BufferedImage image = captureDrawable(shared, component.getWidth(), component.getHeight());
                if (image != null) return image;
            }
            return captureDrawable(component, component.getWidth(), component.getHeight());
        } catch (Throwable failure) {
            diagnose("captureJogl:" + failure.getClass().getName());
            return null;
        }
    }

    private static Object sharedDrawable(final Component component) {
        try {
            for (Component current = component; current != null; current = current.getParent()) {
                final String name = current.getClass().getName();
                if (name.contains("GLJPanelWrapper$b")) {
                    final java.lang.reflect.Field wrapper = current.getClass().getDeclaredField("b");
                    wrapper.setAccessible(true);
                    final Object host = wrapper.get(current);
                    return host.getClass().getMethod("getSharedDrawable").invoke(host);
                }
            }
        } catch (Throwable failure) {
            diagnose("sharedDrawable:" + failure.getClass().getName());
            return null;
        }
        return null;
    }

    private static void drainGlErrors(final Object gl, final Class<?> glType) {
        try {
            final Method glGetError = glType.getMethod("glGetError");
            for (int guard = 0; guard < 16; guard++) {
                if ((Integer) glGetError.invoke(gl) == 0x0500) return;
            }
        } catch (Throwable ignored) {
            // diagnostics only; never fail the capture for drain issues
        }
    }

    private static void drainGlErrors(final Object drawable) {
        try {
            final ClassLoader loader = drawable.getClass().getClassLoader();
            final Class<?> glType = Class.forName("com.jogamp.opengl.GL", false, loader);
            final Object gl = drawable.getClass().getMethod("getGL").invoke(drawable);
            if (gl != null) drainGlErrors(gl, glType);
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    /** FBO-aware manual GL readback: GL_BACK for the default framebuffer, GL_COLOR_ATTACHMENT0 for FBOs. */
    private static BufferedImage readJogl(final Object drawable, final int width, final int height) {
        try {
            if (drawable == null || width <= 0 || height <= 0) {
                diagnose("readJogl:bad-size drawable-null=" + (drawable == null)
                    + " size=" + width + "x" + height);
                return null;
            }
            final ClassLoader loader = drawable.getClass().getClassLoader();
            final Class<?> glType = Class.forName("com.jogamp.opengl.GL", false, loader);
            final Object gl = drawable.getClass().getMethod("getGL").invoke(drawable);
            if (gl == null) {
                diagnose("readJogl:getGL-null class=" + className(drawable));
                return null;
            }
            drainGlErrors(gl, glType);
            final int framebufferBinding = constant(glType, "GL_FRAMEBUFFER_BINDING", 0x8CA6);
            final int colorAttachment0 = constant(glType, "GL_COLOR_ATTACHMENT0", 0x8CE0);
            final int back = constant(glType, "GL_BACK", 0x0405);
            final int rgba = constant(glType, "GL_RGBA", 0x1908);
            final int unsignedByte = constant(glType, "GL_UNSIGNED_BYTE", 0x1401);
            final int[] binding = new int[1];
            glType.getMethod("glGetIntegerv", int.class, int[].class, int.class).invoke(gl, framebufferBinding, binding, 0);
            final ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);
            final Method glReadPixels = glType.getMethod(
                "glReadPixels", int.class, int.class, int.class, int.class, int.class, int.class, java.nio.Buffer.class
            );
            if (binding[0] == 0) {
                readBufferMethod(glType, loader).invoke(gl, back);
                glReadPixels.invoke(gl, 0, 0, width, height, rgba, unsignedByte, pixels);
                drainGlErrors(gl, glType);
            } else {
                // MSAA resolve: blit the bound (multisample) FBO into a fresh single-sample FBO, then read it
                final int framebuffer = constant(glType, "GL_FRAMEBUFFER", 0x8D40);
                final int readFb = constant(glType, "GL_READ_FRAMEBUFFER", 0x8CA8);
                final int drawFb = constant(glType, "GL_DRAW_FRAMEBUFFER", 0x8CA9);
                final int texture2d = constant(glType, "GL_TEXTURE_2D", 0x0DE1);
                final int rgba8 = constant(glType, "GL_RGBA8", 0x8058);
                final int colorBufferBit = constant(glType, "GL_COLOR_BUFFER_BIT", 0x4000);
                final int nearest = constant(glType, "GL_NEAREST", 0x2600);
                final int complete = constant(glType, "GL_FRAMEBUFFER_COMPLETE", 0x8CD5);
                final Method glGenFramebuffers = glType.getMethod("glGenFramebuffers", int.class, int[].class, int.class);
                final Method glBindFramebuffer = glType.getMethod("glBindFramebuffer", int.class, int.class);
                final Method glGenTextures = glType.getMethod("glGenTextures", int.class, int[].class, int.class);
                final Method glBindTexture = glType.getMethod("glBindTexture", int.class, int.class);
                final Method glTexImage2D = glType.getMethod(
                    "glTexImage2D", int.class, int.class, int.class, int.class, int.class,
                    int.class, int.class, int.class, java.nio.Buffer.class
                );
                final Method glFramebufferTexture2D = glType.getMethod(
                    "glFramebufferTexture2D", int.class, int.class, int.class, int.class, int.class
                );
                final Method glBlitFramebuffer = Class.forName("com.jogamp.opengl.GL2ES3", false, loader)
                    .getMethod("glBlitFramebuffer", int.class, int.class, int.class, int.class, int.class,
                        int.class, int.class, int.class, int.class, int.class);
                final Method glCheckFramebufferStatus = glType.getMethod("glCheckFramebufferStatus", int.class);
                final Method glDeleteFramebuffers = glType.getMethod("glDeleteFramebuffers", int.class, int[].class, int.class);
                final Method glDeleteTextures = glType.getMethod("glDeleteTextures", int.class, int[].class, int.class);
                final int[] resolveFramebuffer = new int[1];
                final int[] resolveTexture = new int[1];
                glGenFramebuffers.invoke(gl, 1, resolveFramebuffer, 0);
                glGenTextures.invoke(gl, 1, resolveTexture, 0);
                try {
                    glBindTexture.invoke(gl, texture2d, resolveTexture[0]);
                    glTexImage2D.invoke(gl, texture2d, 0, rgba8, width, height, 0, rgba, unsignedByte, (java.nio.Buffer) null);
                    glBindFramebuffer.invoke(gl, framebuffer, resolveFramebuffer[0]);
                    glFramebufferTexture2D.invoke(gl, framebuffer, colorAttachment0, texture2d, resolveTexture[0], 0);
                    if ((Integer) glCheckFramebufferStatus.invoke(gl, framebuffer) != complete) {
                        diagnose("readJogl:fbo-incomplete");
                        return null;
                    }
                    glBindFramebuffer.invoke(gl, readFb, binding[0]);
                    glBindFramebuffer.invoke(gl, drawFb, resolveFramebuffer[0]);
                    glBlitFramebuffer.invoke(gl, 0, 0, width, height, 0, 0, width, height, colorBufferBit, nearest);
                    glBindFramebuffer.invoke(gl, framebuffer, resolveFramebuffer[0]);
                    drainGlErrors(gl, glType);
                    readBufferMethod(glType, loader).invoke(gl, colorAttachment0);
                } finally {
                    glBindFramebuffer.invoke(gl, framebuffer, binding[0]);   // restore the panel's FBO
                    glBindTexture.invoke(gl, texture2d, 0);
                    glDeleteTextures.invoke(gl, 1, resolveTexture, 0);
                    glDeleteFramebuffers.invoke(gl, 1, resolveFramebuffer, 0);
                }
            }
            glReadPixels.invoke(gl, 0, 0, width, height, rgba, unsignedByte, pixels);
            drainGlErrors(gl, glType);
            final int[] argb = new int[width * height];
            final java.nio.IntBuffer view = pixels.asIntBuffer();
            for (int i = 0; i < argb.length; i++) {
                final int v = view.get(i);
                argb[i] = 0xFF000000 | (v & 0xFF) << 16 | (v & 0xFF00) | (v >> 16) & 0xFF;
            }
            final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < height; y++) {
                image.setRGB(0, y, width, 1, argb, (height - 1 - y) * width, width);
            }
            return image;
        } catch (Throwable failure) {
            diagnose("readJogl:" + failure.getClass().getName());
            return null;
        }
    }

    private static Method readBufferMethod(final Class<?> glType, final ClassLoader loader) throws Exception {
        for (String name : new String[]{"com.jogamp.opengl.GL2ES3", "com.jogamp.opengl.GL2", "com.jogamp.opengl.GL"}) {
            try {
                final Class<?> type = Class.forName(name, false, loader);
                return type.getMethod("glReadBuffer", int.class);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                // try next interface
            }
        }
        throw new NoSuchMethodException("glReadBuffer unavailable in the host JOGL interfaces");
    }

    private static int constant(final Class<?> glType, final String name, final int fallback) {
        try {
            return glType.getField(name).getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    static BufferedImage cropMargins(final BufferedImage image) {
        final int marginX = Math.max(0, image.getWidth() / 40);
        final int marginY = Math.max(0, image.getHeight() / 40);
        final int width = Math.max(1, image.getWidth() - marginX * 2);
        final int height = Math.max(1, image.getHeight() - marginY * 2);
        if (width == image.getWidth() && height == image.getHeight()) return image;
        final BufferedImage cropped = image.getSubimage(marginX, marginY, width, height);
        final BufferedImage copy = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = copy.createGraphics();
        try {
            graphics.drawImage(cropped, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        return copy;
    }

    static BufferedImage scale(final BufferedImage image, final int maxWidth, final int maxHeight) {
        final double ratio = Math.min(1d, Math.min(
            (double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight()
        ));
        final int width = Math.max(1, (int) Math.round(image.getWidth() * ratio));
        final int height = Math.max(1, (int) Math.round(image.getHeight() * ratio));
        if (width == image.getWidth() && height == image.getHeight()) return image;
        final BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    private Component captureRoot() {
        final Object window = RecentMenuChain.resolveWindow(panelResolver);
        return window == null ? null : selectCaptureWindow(Window.getWindows());
    }

    private Component resolveCaptureComponent() {
        try {
            return resolveCaptureComponent(panelResolver);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * Legacy overlay suppression semantics: bounding-box-like overlay components in the
     * capture root are made invisible (setEnabled(false) + setSkipDrawing_testImpl(true),
     * recursively) and returned for restoration. Never fails the capture.
     */
    private static List<Object> suppressOverlays(final Component root) {
        final List<Object> suppressed = new ArrayList<>();
        if (root instanceof Container container) {
            collectOverlays(container, suppressed);
        }
        for (Object overlay : suppressed) {
            suppressEntityDrawingDeep(overlay);
        }
        return suppressed;
    }

    private static void collectOverlays(final Container container, final List<Object> suppressed) {
        for (Component child : container.getComponents()) {
            final String signature = signature(child);
            if (signature.contains("overlay") || signature.contains("boundingbox")) {
                suppressed.add(child);
            }
            if (child instanceof Container nested) {
                collectOverlays(nested, suppressed);
            }
        }
    }

    private static void restoreOverlays(final List<Object> suppressed) {
        for (Object overlay : suppressed) {
            forceEntityVisibleDeep(overlay);
        }
    }

    private static void suppressEntityDrawingDeep(final Object entity) {
        if (entity == null) return;
        invokeQuietly(entity, "setEnabled", Boolean.FALSE);
        invokeQuietly(entity, "setSkipDrawing_testImpl", Boolean.TRUE);
        final Object children = tryInvoke(entity, "getChildren");
        if (children instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                suppressEntityDrawingDeep(child);
            }
        }
    }

    private static void forceEntityVisibleDeep(final Object entity) {
        if (entity == null) return;
        invokeQuietly(entity, "setEnabled", Boolean.TRUE);
        invokeQuietly(entity, "setSkipDrawing_testImpl", Boolean.FALSE);
        final Object children = tryInvoke(entity, "getChildren");
        if (children instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                forceEntityVisibleDeep(child);
            }
        }
    }

    private static void invokeQuietly(final Object target, final String name, final Object value) {
        try {
            target.getClass().getMethod(name, value.getClass()).invoke(target, value);
        } catch (Throwable ignored) {
            // diagnostics only
        }
    }

    private static Object tryInvoke(final Object target, final String name) {
        if (target == null) return null;
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object firstNonNull(final Object[] values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static Component extractSwingComponent(final Object raw) {
        if (raw instanceof Component component) return component;
        final Object jComponent = tryInvoke(raw, "getJComponent");
        if (jComponent instanceof Component component) return component;
        final Object jMenuItem = tryInvoke(raw, "getJMenuItem");
        if (jMenuItem instanceof Component component) return component;
        return null;
    }

    private static boolean samePath(final Path left, final Path right) {
        return RecentMenuChain.pathKey(left).equals(RecentMenuChain.pathKey(right));
    }
}
