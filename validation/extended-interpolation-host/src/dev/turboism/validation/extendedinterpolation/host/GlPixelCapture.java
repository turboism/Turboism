package dev.turboism.validation.extendedinterpolation.host;

import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

/**
 * Pure JDK reflection helper for off-screen OpenGL pixel readback from Live2D Cubism Editor GLJPanel.
 *
 * Designed specifically for differential validation between native and optimized rendering pipelines:
 *   - Locates the underlying JOGL GLAutoDrawable from the editor Component hierarchy, prioritizing the
 *     real visible canvas (com.jogamp.opengl.awt.GLJPanel) over off-screen resource-sharing objects.
 *   - Executes readback strictly via an appended GLEventListener display callback, guaranteeing the readback
 *     occurs AFTER the host's official canvas rendering pass completes in the active FBO, but BEFORE buffer swap/blit.
 *     (Note: GLAutoDrawable.invoke(GLRunnable) executes before listeners and would capture stale previous-frame data).
 *   - Audits and handles both default framebuffer and off-screen FBO (including MSAA resolve blit).
 *     If MSAA resolve fails or is incomplete, readback fails immediately; never falls back to reading multisample FBOs.
 *   - Audits PBO GL_PIXEL_PACK_BUFFER_BINDING and temporarily unbinds it if set, restoring on completion.
 *   - Audits and restores all mutated GL state: GL_FRAMEBUFFER_BINDING, GL_READ_FRAMEBUFFER_BINDING,
 *     GL_DRAW_FRAMEBUFFER_BINDING (restored individually even when 0), GL_READ_BUFFER, GL_TEXTURE_BINDING_2D,
 *     and pack parameters: GL_PACK_ALIGNMENT, GL_PACK_ROW_LENGTH, GL_PACK_SKIP_ROWS, GL_PACK_SKIP_PIXELS.
 *   - Explicit error handling: verifies glGetError() on entry and after every GL state change/readback/restore.
 *     Pre-existing and newly generated GL errors are explicitly thrown; errors are never swallowed.
 *   - Blocks invocation on EDT to prevent deadlocks; requires invocation from a background thread with bounded timeout (&lt;= 15s).
 *   - Analyzes real pixel buffer characteristics: distinct color count, uniformity, all-black detection,
 *     channel ranges, and SHA-256 digest to prevent empty canvas or synthetic fake Robot captures from passing.
 *   - Exports lossless PNG artifacts for visual auditing.
 *   - Zero compile-time external dependencies (pure JDK 17 reflection).
 */
public final class GlPixelCapture {

    public static final long DEFAULT_TIMEOUT_MS = 15_000L;
    public static final long MAX_TIMEOUT_MS = 15_000L;

    // OpenGL Constants
    private static final int GL_NO_ERROR = 0;
    private static final int GL_BACK = 0x0405;
    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int GL_READ_BUFFER = 0x0C02;
    private static final int GL_PACK_ROW_LENGTH = 0x0D02;
    private static final int GL_PACK_SKIP_ROWS = 0x0D03;
    private static final int GL_PACK_SKIP_PIXELS = 0x0D04;
    private static final int GL_PACK_ALIGNMENT = 0x0D05;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_SAMPLE_BUFFERS = 0x80A8;
    private static final int GL_SAMPLES = 0x80A9;
    private static final int GL_PIXEL_PACK_BUFFER = 0x88EB;
    private static final int GL_PIXEL_PACK_BUFFER_BINDING = 0x88ED;
    private static final int GL_FRAMEBUFFER_BINDING = 0x8CA6;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA7;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER = 0x8D40;

    private GlPixelCapture() {}

    /**
     * Immutable capture outcome containing dimensions, real buffer parity evidence, and diagnostics.
     */
    public static final class Result {
        public final int width;
        public final int height;
        public final int distinctPixels;
        public final boolean uniform;
        public final boolean allBlack;
        public final boolean valid;
        public final String sha256;
        public final Path path;
        public final long durationMs;
        public final int minA, maxA, minR, maxR, minG, maxG, minB, maxB;
        public final int boundFramebuffer;
        public final boolean msaaResolved;
        public final BufferedImage image;
        public final int[] argb;

        Result(int width, int height, int distinctPixels, boolean uniform, boolean allBlack,
               boolean valid, String sha256, Path path, long durationMs,
               int minA, int maxA, int minR, int maxR, int minG, int maxG, int minB, int maxB,
               int boundFramebuffer, boolean msaaResolved, BufferedImage image, int[] argb) {
            this.width = width;
            this.height = height;
            this.distinctPixels = distinctPixels;
            this.uniform = uniform;
            this.allBlack = allBlack;
            this.valid = valid;
            this.sha256 = sha256;
            this.path = path;
            this.durationMs = durationMs;
            this.minA = minA;
            this.maxA = maxA;
            this.minR = minR;
            this.maxR = maxR;
            this.minG = minG;
            this.maxG = maxG;
            this.minB = minB;
            this.maxB = maxB;
            this.boundFramebuffer = boundFramebuffer;
            this.msaaResolved = msaaResolved;
            this.image = image;
            this.argb = argb;
        }

        /**
         * Validates that this capture represents a real, non-empty, rendered canvas rather than an
         * unpainted window, uninitialized buffer, or artificial single-color fake readback.
         */
        public void requireValid() {
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException("GL pixel capture invalid: dimensions are " + width + "x" + height);
            }
            if (allBlack) {
                throw new IllegalStateException("GL pixel capture invalid: buffer is all black (0x00000000 or 0xFF000000)");
            }
            if (uniform || distinctPixels <= 1) {
                throw new IllegalStateException("GL pixel capture invalid: buffer is uniform/blank (distinctPixels="
                        + distinctPixels + ", minR=" + minR + ", maxR=" + maxR + "); not valid parity evidence");
            }
            if (!valid) {
                throw new IllegalStateException("GL pixel capture marked invalid");
            }
        }

        /**
         * Checks exact pixel parity with another capture result.
         */
        public boolean matches(Result other) {
            if (other == null) return false;
            return this.width == other.width && this.height == other.height && Objects.equals(this.sha256, other.sha256);
        }

        /**
         * Detailed differential analysis between this and another capture.
         */
        public String diff(Result other) {
            if (other == null) return "other Result is null";
            if (this.width != other.width || this.height != other.height) {
                return "DIMENSION_MISMATCH: " + this.width + "x" + this.height + " vs " + other.width + "x" + other.height;
            }
            if (Objects.equals(this.sha256, other.sha256)) {
                return "MATCH: sha256=" + sha256 + " (distinctPixels=" + distinctPixels + ")";
            }
            if (this.argb == null || other.argb == null || this.argb.length != other.argb.length) {
                return "SHA_MISMATCH: nativeSha=" + this.sha256 + " vs optSha=" + other.sha256;
            }

            int changed = 0;
            int minX = width, minY = height, maxX = -1, maxY = -1;
            int maxDelta = 0;
            StringBuilder samples = new StringBuilder();

            for (int i = 0; i < argb.length; i++) {
                int p1 = argb[i];
                int p2 = other.argb[i];
                if (p1 == p2) continue;

                int x = i % width;
                int y = i / width;
                changed++;
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;

                for (int shift = 0; shift < 32; shift += 8) {
                    int c1 = (p1 >>> shift) & 0xFF;
                    int c2 = (p2 >>> shift) & 0xFF;
                    int delta = Math.abs(c1 - c2);
                    if (delta > maxDelta) maxDelta = delta;
                }

                if (changed <= 8) {
                    samples.append("(").append(x).append(",").append(y).append("):")
                            .append(String.format("#%08X->#%08X;", p1, p2));
                }
            }

            return String.format(
                    "MISMATCH: changedPixels=%d/%d (%.2f%%), bounds=[%d,%d -> %d,%d], maxChannelDelta=%d, samples=%s",
                    changed, argb.length, (changed * 100.0) / argb.length, minX, minY, maxX, maxY, maxDelta, samples);
        }

        public String summary() {
            return String.format(
                    "width=%d, height=%d, distinctPixels=%d, uniform=%b, allBlack=%b, valid=%b, sha256=%s, durationMs=%d, fbo=%d, msaaResolved=%b, path=%s",
                    width, height, distinctPixels, uniform, allBlack, valid, sha256, durationMs, boundFramebuffer, msaaResolved,
                    path != null ? path.getFileName() : "in-memory");
        }

        @Override
        public String toString() {
            return "GlPixelCapture.Result[" + summary() + "]";
        }
    }

    /**
     * Captures the rendered frame from the specified Component and saves it to outputPath.
     * Must be called from a background thread; rejects EDT calls to prevent event loop deadlock.
     */
    public static Result capture(Component component, Path outputPath) throws Exception {
        return capture(component, outputPath, null, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Captures the rendered frame from the specified Component with custom timeout.
     * Must be called from a background thread; rejects EDT calls to prevent event loop deadlock.
     */
    public static Result capture(Component component, Path outputPath, long timeoutMs) throws Exception {
        return capture(component, outputPath, null, timeoutMs);
    }

    /**
     * Captures the rendered frame from the specified Component, executing preDisplayTrigger (if non-null)
     * after listener registration and before triggering the redraw.
     * Must be called from a background thread; rejects EDT calls to prevent event loop deadlock.
     */
    public static Result capture(Component component, Path outputPath, Runnable preDisplayTrigger, long timeoutMs) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("GlPixelCapture.capture must NOT be called on EDT as it blocks waiting for rendering to complete; call from a background worker thread");
        }
        if (component == null) {
            throw new IllegalArgumentException("Target component cannot be null");
        }
        long effectiveTimeout = Math.min(Math.max(100L, timeoutMs), MAX_TIMEOUT_MS);
        long startTime = System.currentTimeMillis();

        Object drawable = findGlAutoDrawable(component);
        if (drawable == null) {
            throw new IllegalStateException("Failed to locate JOGL GLAutoDrawable in component hierarchy of: "
                    + component.getClass().getName());
        }

        final ClassLoader loader = resolveClassLoader(drawable, component);
        Class<?> autoDrawableClass = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        Class<?> listenerClass = Class.forName("com.jogamp.opengl.GLEventListener", false, loader);

        Method addListenerMethod = autoDrawableClass.getMethod("addGLEventListener", listenerClass);
        Method removeListenerMethod = autoDrawableClass.getMethod("removeGLEventListener", listenerClass);
        Method displayMethod = autoDrawableClass.getMethod("display");

        CompletableFuture<Result> future = new CompletableFuture<>();
        AtomicBoolean captured = new AtomicBoolean(false);

        // GLEventListener dynamic proxy: executes strictly after Cubism's official canvas listener
        InvocationHandler listenerHandler = (proxy, method, args) -> {
            String name = method.getName();
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if ("toString".equals(name)) return "TurboismGlPixelCaptureListener@" + Integer.toHexString(System.identityHashCode(proxy));

            if ("display".equals(name) && args != null && args.length >= 1) {
                if (captured.compareAndSet(false, true)) {
                    try {
                        Object activeDrawable = args[0];
                        Result res = performReadback(activeDrawable, loader, outputPath, startTime);
                        future.complete(res);
                    } catch (Throwable failure) {
                        future.completeExceptionally(failure);
                    }
                }
            }
            return null;
        };

        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerClass}, listenerHandler);

        try {
            // 1. Append listener to the end of the JOGL listener chain
            addListenerMethod.invoke(drawable, listener);

            // 2. Run optional trigger action (e.g., viewContext.repaintCanvas) if provided
            if (preDisplayTrigger != null) {
                preDisplayTrigger.run();
            }

            // 3. Trigger display cycle on GLAutoDrawable; errors are propagated to the future
            triggerDisplay(drawable, displayMethod, future);

            // 4. Await result with bounded timeout <= 15s
            try {
                return future.get(effectiveTimeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                throw new TimeoutException("GL pixel readback timed out after " + effectiveTimeout + "ms on "
                        + drawable.getClass().getName());
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof Exception e) throw e;
                throw new RuntimeException("GL pixel readback failed", cause);
            }
        } finally {
            // 5. Always clean up listener to prevent leaking or polluting subsequent frames
            try {
                removeListenerMethod.invoke(drawable, listener);
            } catch (Throwable ignored) {}
        }
    }

    private static void triggerDisplay(Object drawable, Method displayMethod, CompletableFuture<Result> future) {
        SwingUtilities.invokeLater(() -> {
            try {
                displayMethod.invoke(drawable);
            } catch (Throwable t) {
                Throwable cause = (t instanceof InvocationTargetException ite && ite.getCause() != null)
                        ? ite.getCause() : t;
                future.completeExceptionally(cause);
            }
        });
    }

    /**
     * Resolves the primary ClassLoader from drawable, component, or contextual loaders.
     */
    private static ClassLoader resolveClassLoader(Object drawable, Component component) {
        if (drawable != null && drawable.getClass().getClassLoader() != null) {
            return drawable.getClass().getClassLoader();
        }
        if (component != null && component.getClass().getClassLoader() != null) {
            return component.getClass().getClassLoader();
        }
        ClassLoader ctxLoader = Thread.currentThread().getContextClassLoader();
        return ctxLoader != null ? ctxLoader : ClassLoader.getSystemClassLoader();
    }

    /**
     * Searches for the primary JOGL GLAutoDrawable canvas starting from the given Component.
     * Prioritizes the actual visible canvas (com.jogamp.opengl.awt.GLJPanel) over off-screen or
     * resource-sharing objects.
     */
    public static Object findGlAutoDrawable(Component component) {
        if (component == null) return null;
        ClassLoader loader = resolveClassLoader(null, component);

        Class<?> autoDrawableClass = null;
        try {
            autoDrawableClass = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        } catch (ClassNotFoundException ignored) {}

        if (autoDrawableClass == null) {
            try {
                autoDrawableClass = Class.forName("com.jogamp.opengl.GLAutoDrawable");
            } catch (ClassNotFoundException ignored) {}
        }
        if (autoDrawableClass == null) return null;

        // Phase 1: Search for showing com.jogamp.opengl.awt.GLJPanel canvas (the real rendering surface)
        Component found = searchCanvas(component, true, true, autoDrawableClass);
        if (found != null) return found;

        // Phase 2: Search for any showing GLAutoDrawable
        found = searchCanvas(component, true, false, autoDrawableClass);
        if (found != null) return found;

        // Phase 3: Search for non-showing GLJPanel canvas
        found = searchCanvas(component, false, true, autoDrawableClass);
        if (found != null) return found;

        // Phase 4: Search for any GLAutoDrawable in component tree
        found = searchCanvas(component, false, false, autoDrawableClass);
        if (found != null) return found;

        // Phase 5: Ascend to root window and search downwards from root
        Component root = component;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        if (root != component) {
            found = searchCanvas(root, true, true, autoDrawableClass);
            if (found != null) return found;
            found = searchCanvas(root, true, false, autoDrawableClass);
            if (found != null) return found;
        }

        return null;
    }

    private static Component searchCanvas(Component root, boolean requireShowing, boolean requireExactGlJPanel, Class<?> autoDrawableClass) {
        Deque<Component> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Component c = queue.poll();
            if (c == null) continue;
            if (!requireShowing || c.isShowing()) {
                boolean isGlJPanel = c.getClass().getName().equals("com.jogamp.opengl.awt.GLJPanel");
                boolean isAutoDrawable = autoDrawableClass.isInstance(c);
                if (requireExactGlJPanel) {
                    if (isGlJPanel && isAutoDrawable) return c;
                } else {
                    if (isAutoDrawable) return c;
                }
            }
            if (c instanceof Container container) {
                for (Component child : container.getComponents()) {
                    if (child != null) queue.add(child);
                }
            }
        }
        return null;
    }

    /**
     * Executes the low-level OpenGL readback and state management inside the current GL context.
     */
    private static Result performReadback(Object drawable, ClassLoader loader, Path outputPath, long startTime) throws Exception {
        Method getGlMethod = drawable.getClass().getMethod("getGL");
        Object gl = getGlMethod.invoke(drawable);
        if (gl == null) {
            throw new IllegalStateException("drawable.getGL() returned null");
        }

        GlContext ctx = new GlContext(gl, loader);

        // Entrance check: verify zero pre-existing OpenGL errors
        int preExistingError = ctx.rawGetError();
        if (preExistingError != GL_NO_ERROR) {
            throw new IllegalStateException("Pre-existing OpenGL error before readback: " + glErrorString(preExistingError));
        }

        // 1. Determine dimensions (prefer physical surface width/height, fallback to viewport/component)
        int width = getSurfaceDimension(drawable, "getSurfaceWidth");
        int height = getSurfaceDimension(drawable, "getSurfaceHeight");

        if (width <= 0 || height <= 0) {
            int[] vp = new int[4];
            if (ctx.glGetIntegerv != null) {
                ctx.glGetIntegerv(GL_VIEWPORT, vp, 0);
                width = vp[2];
                height = vp[3];
            }
        }
        if (width <= 0 || height <= 0) {
            if (drawable instanceof Component comp) {
                width = comp.getWidth();
                height = comp.getHeight();
            }
        }
        if (width <= 0 || height <= 0) {
            throw new IllegalStateException("Unable to resolve valid surface dimensions: " + width + "x" + height);
        }

        // 2. Audit and record initial OpenGL states for restoration
        int[] origFbBinding = new int[1];
        int[] origReadFbBinding = new int[1];
        int[] origDrawFbBinding = new int[1];
        int[] origReadBuffer = new int[1];
        int[] origPackAlignment = new int[]{4};
        int[] origPackRowLength = new int[]{0};
        int[] origPackSkipRows = new int[]{0};
        int[] origPackSkipPixels = new int[]{0};
        int[] origTex2d = new int[1];
        int[] origPackPbo = new int[1];
        int[] sampleBuffers = new int[1];
        int[] samples = new int[1];

        ctx.glGetIntegerv(GL_FRAMEBUFFER_BINDING, origFbBinding, 0);

        // This carrier is pinned to the Editor's desktop GL3 renderer. Missing
        // state queries invalidate the proof; there is no capability fallback.
        boolean hasSeparateReadDraw = true;
        boolean hasPackRowLength = true, hasPackSkipRows = true, hasPackSkipPixels = true, hasPackPbo = true;
        ctx.glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, origReadFbBinding, 0);
        ctx.glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, origDrawFbBinding, 0);
        ctx.glGetIntegerv(GL_READ_BUFFER, origReadBuffer, 0);
        ctx.glGetIntegerv(GL_PACK_ALIGNMENT, origPackAlignment, 0);
        ctx.glGetIntegerv(GL_PACK_ROW_LENGTH, origPackRowLength, 0);
        ctx.glGetIntegerv(GL_PACK_SKIP_ROWS, origPackSkipRows, 0);
        ctx.glGetIntegerv(GL_PACK_SKIP_PIXELS, origPackSkipPixels, 0);
        ctx.glGetIntegerv(GL_TEXTURE_BINDING_2D, origTex2d, 0);
        ctx.glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, origPackPbo, 0);
        ctx.glGetIntegerv(GL_SAMPLE_BUFFERS, sampleBuffers, 0);
        ctx.glGetIntegerv(GL_SAMPLES, samples, 0);

        // Active framebuffer is strictly the READ framebuffer binding (even if 0)
        int activeReadFb = hasSeparateReadDraw ? origReadFbBinding[0] : origFbBinding[0];
        boolean msaaResolved = false;

        // Allocate tightly packed direct byte buffer for RGBA readback
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.LITTLE_ENDIAN);

        Exception readbackException = null;
        try {
            // Configure pack alignment to 1 for dense row packing
            ctx.glPixelStorei(GL_PACK_ALIGNMENT, 1);
            if (hasPackRowLength) ctx.glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            if (hasPackSkipRows) ctx.glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            if (hasPackSkipPixels) ctx.glPixelStorei(GL_PACK_SKIP_PIXELS, 0);

            // Audit PBO pack binding: unbind if bound so glReadPixels targets client ByteBuffer
            if (hasPackPbo && origPackPbo[0] != 0) {
                ctx.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
            }

            if (activeReadFb == 0) {
                // Default framebuffer: read from GL_BACK
                ctx.glReadBuffer(GL_BACK);
                ctx.glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            } else {
                // FBO path: inspect multisampling (MSAA)
                boolean isMultisample = sampleBuffers[0] > 0 || samples[0] > 1;

                if (isMultisample) {
                    if (ctx.glBlitFramebuffer == null || ctx.glGenFramebuffers == null || ctx.glGenTextures == null) {
                        throw new IllegalStateException(String.format(
                                "Active framebuffer %d is multisampled (sampleBuffers=%d, samples=%d), but glBlitFramebuffer/FBO is unavailable for MSAA resolve",
                                activeReadFb, sampleBuffers[0], samples[0]));
                    }

                    int[] resolveFbo = new int[1];
                    int[] resolveTex = new int[1];
                    boolean resolveCreated = false;

                    try {
                        ctx.glGenFramebuffers(1, resolveFbo, 0);
                        ctx.glGenTextures(1, resolveTex, 0);
                        resolveCreated = true;

                        ctx.glBindTexture(GL_TEXTURE_2D, resolveTex[0]);
                        ctx.glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
                        ctx.glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo[0]);
                        ctx.glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, resolveTex[0], 0);

                        int status = ctx.glCheckFramebufferStatus(GL_FRAMEBUFFER);
                        if (status != GL_FRAMEBUFFER_COMPLETE) {
                            throw new IllegalStateException(String.format(
                                    "MSAA resolve framebuffer incomplete: status=0x%04X (%s) for FB %d",
                                    status, glFramebufferStatusString(status), resolveFbo[0]));
                        }

                        // Blit multisample FBO to resolve FBO
                        ctx.glBindFramebuffer(GL_READ_FRAMEBUFFER, activeReadFb);
                        ctx.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolveFbo[0]);
                        ctx.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

                        ctx.glBindFramebuffer(GL_FRAMEBUFFER, resolveFbo[0]);
                        ctx.glReadBuffer(GL_COLOR_ATTACHMENT0);
                        ctx.glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                        msaaResolved = true;
                    } finally {
                        if (resolveCreated) {
                            try {
                                if (hasSeparateReadDraw) {
                                    ctx.glBindFramebuffer(GL_READ_FRAMEBUFFER, activeReadFb);
                                    ctx.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, origDrawFbBinding[0]);
                                } else {
                                    ctx.glBindFramebuffer(GL_FRAMEBUFFER, activeReadFb);
                                }
                            } catch (Throwable ignored) {}
                            try { ctx.glBindTexture(GL_TEXTURE_2D, origTex2d[0]); } catch (Throwable ignored) {}
                            try { ctx.glDeleteTextures(1, resolveTex, 0); } catch (Throwable ignored) {}
                            try { ctx.glDeleteFramebuffers(1, resolveFbo, 0); } catch (Throwable ignored) {}
                        }
                    }
                } else {
                    // Single-sample FBO: direct read from activeReadFb
                    ctx.glBindFramebuffer(GL_FRAMEBUFFER, activeReadFb);
                    ctx.glReadBuffer(GL_COLOR_ATTACHMENT0);
                    ctx.glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                }
            }
        } catch (Exception e) {
            readbackException = e;
        } finally {
            Throwable restoreError = null;

            // 1. Restore texture binding
            try {
                ctx.glBindTexture(GL_TEXTURE_2D, origTex2d[0]);
            } catch (Throwable t) {
                if (restoreError == null) restoreError = t;
            }

            // 2. Restore PBO binding if was bound
            try {
                if (hasPackPbo && origPackPbo[0] != 0 && ctx.glBindBuffer != null) {
                    ctx.glBindBuffer(GL_PIXEL_PACK_BUFFER, origPackPbo[0]);
                }
            } catch (Throwable t) {
                if (restoreError == null) restoreError = t;
            }

            // 3. Restore read and draw framebuffers explicitly (including 0)
            try {
                if (hasSeparateReadDraw) {
                    ctx.glBindFramebuffer(GL_READ_FRAMEBUFFER, origReadFbBinding[0]);
                    ctx.glBindFramebuffer(GL_DRAW_FRAMEBUFFER, origDrawFbBinding[0]);
                } else {
                    ctx.glBindFramebuffer(GL_FRAMEBUFFER, origFbBinding[0]);
                }
            } catch (Throwable t) {
                if (restoreError == null) restoreError = t;
            }

            // 4. Restore read buffer
            try {
                if (ctx.glReadBuffer != null) {
                    ctx.glReadBuffer(origReadBuffer[0]);
                }
            } catch (Throwable t) {
                if (restoreError == null) restoreError = t;
            }

            // 5. Restore pack parameters
            try {
                ctx.glPixelStorei(GL_PACK_ALIGNMENT, origPackAlignment[0]);
                if (hasPackRowLength) ctx.glPixelStorei(GL_PACK_ROW_LENGTH, origPackRowLength[0]);
                if (hasPackSkipRows) ctx.glPixelStorei(GL_PACK_SKIP_ROWS, origPackSkipRows[0]);
                if (hasPackSkipPixels) ctx.glPixelStorei(GL_PACK_SKIP_PIXELS, origPackSkipPixels[0]);
            } catch (Throwable t) {
                if (restoreError == null) restoreError = t;
            }

            // 6. Verify zero remaining GL errors after restore
            try {
                ctx.checkGlError("state_restoration");
            } catch (Throwable t) {
                if (restoreError == null) restoreError = t;
            }

            if (readbackException != null) {
                if (restoreError != null) readbackException.addSuppressed(restoreError);
                throw readbackException;
            }
            if (restoreError != null) {
                if (restoreError instanceof Exception e) throw e;
                throw new IllegalStateException("GL state restoration failed", restoreError);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        // 3. Process raw pixels: OpenGL bottom-left to BufferedImage top-left row inversion
        pixels.rewind();
        int[] argb = new int[width * height];
        Set<Integer> distinctColors = new HashSet<>();
        int minA = 255, maxA = 0, minR = 255, maxR = 0, minG = 255, maxG = 0, minB = 255, maxB = 0;

        for (int y = 0; y < height; y++) {
            int srcY = height - 1 - y; // vertical inversion
            int rowOffset = srcY * width * 4;
            for (int x = 0; x < width; x++) {
                int offset = rowOffset + x * 4;
                int r = pixels.get(offset) & 0xFF;
                int g = pixels.get(offset + 1) & 0xFF;
                int b = pixels.get(offset + 2) & 0xFF;
                int a = pixels.get(offset + 3) & 0xFF;

                int pixelArgb = (a << 24) | (r << 16) | (g << 8) | b;
                argb[y * width + x] = pixelArgb;

                if (distinctColors.size() < 4096) {
                    distinctColors.add(pixelArgb);
                }

                if (a < minA) minA = a; if (a > maxA) maxA = a;
                if (r < minR) minR = r; if (r > maxR) maxR = r;
                if (g < minG) minG = g; if (g > maxG) maxG = g;
                if (b < minB) minB = b; if (b > maxB) maxB = b;
            }
        }

        int distinctPixels = distinctColors.size();
        boolean uniform = (distinctPixels <= 1);
        boolean allBlack = uniform && (argb[0] == 0x00000000 || argb[0] == 0xFF000000);
        boolean valid = !uniform && !allBlack && width > 0 && height > 0;

        // 4. SHA-256 calculation over canonical ARGB pixel sequence
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        ByteBuffer digestChunk = ByteBuffer.allocate(Math.min(width * 4, 65536));
        for (int val : argb) {
            if (!digestChunk.hasRemaining()) {
                digestChunk.flip();
                sha.update(digestChunk);
                digestChunk.clear();
            }
            digestChunk.putInt(val);
        }
        if (digestChunk.position() > 0) {
            digestChunk.flip();
            sha.update(digestChunk);
        }
        String sha256 = HexFormat.of().formatHex(sha.digest());

        // 5. Construct BufferedImage and persist PNG artifact if requested
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);

        if (outputPath != null) {
            Path parent = outputPath.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (OutputStream os = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                if (!ImageIO.write(image, "PNG", os)) {
                    throw new IOException("No ImageIO PNG writer available to write " + outputPath);
                }
            }
        }

        return new Result(width, height, distinctPixels, uniform, allBlack, valid, sha256, outputPath,
                durationMs, minA, maxA, minR, maxR, minG, maxG, minB, maxB, activeReadFb, msaaResolved, image, argb);
    }

    private static int getSurfaceDimension(Object drawable, String methodName) {
        try {
            Method m = drawable.getClass().getMethod(methodName);
            Object res = m.invoke(drawable);
            if (res instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {}
        return 0;
    }

    private static String glErrorString(int err) {
        return switch (err) {
            case 0 -> "GL_NO_ERROR";
            case 0x0500 -> "GL_INVALID_ENUM (0x0500)";
            case 0x0501 -> "GL_INVALID_VALUE (0x0501)";
            case 0x0502 -> "GL_INVALID_OPERATION (0x0502)";
            case 0x0503 -> "GL_STACK_OVERFLOW (0x0503)";
            case 0x0504 -> "GL_STACK_UNDERFLOW (0x0504)";
            case 0x0505 -> "GL_OUT_OF_MEMORY (0x0505)";
            case 0x0506 -> "GL_INVALID_FRAMEBUFFER_OPERATION (0x0506)";
            default -> String.format("GL_ERROR_0x%04X", err);
        };
    }

    private static String glFramebufferStatusString(int status) {
        return switch (status) {
            case 0x8CD5 -> "GL_FRAMEBUFFER_COMPLETE (0x8CD5)";
            case 0x8CD6 -> "GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT (0x8CD6)";
            case 0x8CD7 -> "GL_FRAMEBUFFER_INCOMPLETE_MISSING_ATTACHMENT (0x8CD7)";
            case 0x8CD9 -> "GL_FRAMEBUFFER_INCOMPLETE_DIMENSIONS (0x8CD9)";
            case 0x8CDA -> "GL_FRAMEBUFFER_INCOMPLETE_FORMATS (0x8CDA)";
            case 0x8CDB -> "GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER (0x8CDB)";
            case 0x8CDC -> "GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER (0x8CDC)";
            case 0x8CDD -> "GL_FRAMEBUFFER_UNSUPPORTED (0x8CDD)";
            case 0x8D56 -> "GL_FRAMEBUFFER_INCOMPLETE_MULTISAMPLE (0x8D56)";
            default -> String.format("0x%04X", status);
        };
    }

    /**
     * Reflective adapter around the underlying JOGL GL pipeline.
     */
    private static final class GlContext {
        final Object gl;
        final Method glGetError;
        final Method glGetIntegerv;
        final Method glPixelStorei;
        final Method glReadPixels;
        final Method glReadBuffer;
        final Method glBindBuffer;
        final Method glBindFramebuffer;
        final Method glBindTexture;
        final Method glGenFramebuffers;
        final Method glDeleteFramebuffers;
        final Method glGenTextures;
        final Method glDeleteTextures;
        final Method glTexImage2D;
        final Method glFramebufferTexture2D;
        final Method glCheckFramebufferStatus;
        final Method glBlitFramebuffer;

        GlContext(Object gl, ClassLoader loader) {
            this.gl = gl;
            this.glGetError = findMethod(gl.getClass(), "glGetError", 0);
            this.glGetIntegerv = findMethod(gl.getClass(), "glGetIntegerv", 3);
            this.glPixelStorei = findMethod(gl.getClass(), "glPixelStorei", 2);
            this.glReadPixels = findReadPixelsMethod(gl.getClass());
            this.glReadBuffer = findReadBufferMethod(gl.getClass(), loader);
            this.glBindBuffer = findMethod(gl.getClass(), "glBindBuffer", 2);
            this.glBindFramebuffer = findMethod(gl.getClass(), "glBindFramebuffer", 2);
            this.glBindTexture = findMethod(gl.getClass(), "glBindTexture", 2);
            this.glGenFramebuffers = findMethod(gl.getClass(), "glGenFramebuffers", 3);
            this.glDeleteFramebuffers = findMethod(gl.getClass(), "glDeleteFramebuffers", 3);
            this.glGenTextures = findMethod(gl.getClass(), "glGenTextures", 3);
            this.glDeleteTextures = findMethod(gl.getClass(), "glDeleteTextures", 3);
            this.glTexImage2D = findTexImage2DMethod(gl.getClass());
            this.glFramebufferTexture2D = findMethod(gl.getClass(), "glFramebufferTexture2D", 5);
            this.glCheckFramebufferStatus = findMethod(gl.getClass(), "glCheckFramebufferStatus", 1);
            this.glBlitFramebuffer = findMethod(gl.getClass(), "glBlitFramebuffer", 10);
            for(Method method:new Method[]{glGetError,glGetIntegerv,glPixelStorei,glReadPixels,
                    glReadBuffer,glBindBuffer,glBindFramebuffer,glBindTexture,glGenFramebuffers,
                    glDeleteFramebuffers,glGenTextures,glDeleteTextures,glTexImage2D,
                    glFramebufferTexture2D,glCheckFramebufferStatus,glBlitFramebuffer})
                if(method==null) throw new IllegalStateException("Missing required desktop GL3 entrypoint");
        }

        int rawGetError() {
            try {
                return ((Number) glGetError.invoke(gl)).intValue();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to audit GL errors",failure);
            }
        }

        void checkGlError(String operation) {
            int err = rawGetError();
            if (err != GL_NO_ERROR) {
                throw new IllegalStateException("OpenGL error during " + operation + ": " + glErrorString(err));
            }
        }

        void glGetIntegerv(int pname, int[] params, int offset) throws Exception {
            if (glGetIntegerv == null) throw new NoSuchMethodException("glGetIntegerv");
            glGetIntegerv.invoke(gl, pname, params, offset);
            checkGlError("glGetIntegerv(0x" + Integer.toHexString(pname) + ")");
        }

        void glPixelStorei(int pname, int param) throws Exception {
            if (glPixelStorei == null) throw new NoSuchMethodException("glPixelStorei");
            glPixelStorei.invoke(gl, pname, param);
            checkGlError("glPixelStorei(0x" + Integer.toHexString(pname) + ", " + param + ")");
        }

        void glReadBuffer(int mode) throws Exception {
            if (glReadBuffer == null) throw new NoSuchMethodException("glReadBuffer");
            glReadBuffer.invoke(gl, mode);
            checkGlError("glReadBuffer(0x" + Integer.toHexString(mode) + ")");
        }

        void glBindBuffer(int target, int buffer) throws Exception {
            if (glBindBuffer == null) return;
            glBindBuffer.invoke(gl, target, buffer);
            checkGlError("glBindBuffer(0x" + Integer.toHexString(target) + ", " + buffer + ")");
        }

        void glBindFramebuffer(int target, int fb) throws Exception {
            if (glBindFramebuffer == null) throw new NoSuchMethodException("glBindFramebuffer");
            glBindFramebuffer.invoke(gl, target, fb);
            checkGlError("glBindFramebuffer(0x" + Integer.toHexString(target) + ", " + fb + ")");
        }

        void glBindTexture(int target, int tex) throws Exception {
            if (glBindTexture == null) throw new NoSuchMethodException("glBindTexture");
            glBindTexture.invoke(gl, target, tex);
            checkGlError("glBindTexture(0x" + Integer.toHexString(target) + ", " + tex + ")");
        }

        void glReadPixels(int x, int y, int width, int height, int format, int type, Buffer data) throws Exception {
            if (glReadPixels == null) throw new NoSuchMethodException("glReadPixels");
            glReadPixels.invoke(gl, x, y, width, height, format, type, data);
            checkGlError("glReadPixels");
        }

        void glGenFramebuffers(int n, int[] ids, int offset) throws Exception {
            if (glGenFramebuffers != null) {
                glGenFramebuffers.invoke(gl, n, ids, offset);
                checkGlError("glGenFramebuffers");
            }
        }

        void glDeleteFramebuffers(int n, int[] ids, int offset) throws Exception {
            if (glDeleteFramebuffers != null) {
                glDeleteFramebuffers.invoke(gl, n, ids, offset);
                checkGlError("glDeleteFramebuffers");
            }
        }

        void glGenTextures(int n, int[] ids, int offset) throws Exception {
            if (glGenTextures != null) {
                glGenTextures.invoke(gl, n, ids, offset);
                checkGlError("glGenTextures");
            }
        }

        void glDeleteTextures(int n, int[] ids, int offset) throws Exception {
            if (glDeleteTextures != null) {
                glDeleteTextures.invoke(gl, n, ids, offset);
                checkGlError("glDeleteTextures");
            }
        }

        void glTexImage2D(int target, int level, int internalFormat, int width, int height,
                          int border, int format, int type, Buffer pixels) throws Exception {
            if (glTexImage2D != null) {
                glTexImage2D.invoke(gl, target, level, internalFormat, width, height, border, format, type, pixels);
                checkGlError("glTexImage2D");
            }
        }

        void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) throws Exception {
            if (glFramebufferTexture2D != null) {
                glFramebufferTexture2D.invoke(gl, target, attachment, textarget, texture, level);
                checkGlError("glFramebufferTexture2D");
            }
        }

        int glCheckFramebufferStatus(int target) throws Exception {
            if (glCheckFramebufferStatus != null) {
                Object res = glCheckFramebufferStatus.invoke(gl, target);
                checkGlError("glCheckFramebufferStatus");
                if (res instanceof Number n) return n.intValue();
            }
            return 0;
        }

        void glBlitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1,
                               int dstX0, int dstY0, int dstX1, int dstY1, int mask, int filter) throws Exception {
            if (glBlitFramebuffer != null) {
                glBlitFramebuffer.invoke(gl, srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
                checkGlError("glBlitFramebuffer");
            }
        }

        private static Method findMethod(Class<?> clazz, String name, int paramCount) {
            if (clazz == null) return null;
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    m.setAccessible(true);
                    return m;
                }
            }
            for (Class<?> iface : clazz.getInterfaces()) {
                Method m = findMethod(iface, name, paramCount);
                if (m != null) return m;
            }
            return findMethod(clazz.getSuperclass(), name, paramCount);
        }

        private static Method findReadPixelsMethod(Class<?> clazz) {
            if (clazz == null) return null;
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals("glReadPixels") && m.getParameterCount() == 7) {
                    Class<?> last = m.getParameterTypes()[6];
                    if (Buffer.class.isAssignableFrom(last)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            return null;
        }

        private static Method findTexImage2DMethod(Class<?> clazz) {
            if (clazz == null) return null;
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals("glTexImage2D") && m.getParameterCount() == 9) {
                    Class<?> last = m.getParameterTypes()[8];
                    if (Buffer.class.isAssignableFrom(last)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            }
            return null;
        }

        private static Method findReadBufferMethod(Class<?> clazz, ClassLoader loader) {
            Method m = findMethod(clazz, "glReadBuffer", 1);
            if (m != null) return m;
            String[] candidateInterfaces = {"com.jogamp.opengl.GL2ES3", "com.jogamp.opengl.GL2", "com.jogamp.opengl.GL"};
            for (String name : candidateInterfaces) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    m = findMethod(type, "glReadBuffer", 1);
                    if (m != null) return m;
                } catch (Throwable ignored) {}
            }
            return null;
        }
    }
}
