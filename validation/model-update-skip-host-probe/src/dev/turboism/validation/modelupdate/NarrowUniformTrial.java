package dev.turboism.validation.modelupdate;

import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComponent;

/**
 * Test-only control of the production narrow hook. Never installs a GL proxy.
 * Explicit paint capture is outside timed windows and must execute the real
 * hooked render scope; a reused image or unsupported context cannot certify it.
 */
final class NarrowUniformTrial implements AutoCloseable {
    static final String ENABLE = "turboism.optimization.uniformLocationCache";
    static final String STATS = "turboism.uniform-location.stats";
    private final String previous = System.getProperty(ENABLE);
    private JComponent drawable;
    private Object listener;
    private java.lang.reflect.Method removeListener;
    private volatile long completedDisplays;
    private volatile boolean ownerLost;

    static NarrowUniformTrial attach(JComponent canvas) throws Exception {
        requireEdt();
        NarrowUniformTrial trial = new NarrowUniformTrial();
        ClassLoader loader = canvas.getClass().getClassLoader();
        Class<?> auto = Class.forName("com.jogamp.opengl.GLAutoDrawable", false, loader);
        Class<?> callback = Class.forName("com.jogamp.opengl.GLEventListener", false, loader);
        trial.drawable = canvas;
        trial.removeListener = auto.getMethod("removeGLEventListener", callback);
        trial.listener = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "TurboismNarrowHookDisplayCounter";
            };
            if (method.getName().equals("display")) {
                if (args == null || args[0] != canvas) trial.ownerLost = true;
                else trial.completedDisplays++;
            }
            return null;
        });
        auto.getMethod("addGLEventListener", callback).invoke(canvas, trial.listener);
        return trial;
    }

    void setEnabled(boolean enabled) {
        requireEdt();
        System.setProperty(ENABLE, Boolean.toString(enabled));
    }
    Map<String, Long> snapshot() {
        if (ownerLost) throw new IllegalStateException("display counter drawable identity changed");
        Object callback = System.getProperties().get(STATS);
        if (!(callback instanceof Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> values)) {
            throw new IllegalStateException("production uniform-hook statistics missing; installation not verified");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key instanceof String name && value instanceof Number number) result.put(name, number.longValue());
        });
        result.put("completedDisplayFrames", completedDisplays);
        return Map.copyOf(result);
    }
    FrameReadback capture(JComponent canvas) {
        requireEdt();
        int width = canvas.getWidth(), height = canvas.getHeight();
        if (width <= 0 || height <= 0 || (long) width * height > 16_777_216L) {
            throw new IllegalArgumentException("invalid capture dimensions");
        }
        Map<String, Long> before = snapshot();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setClip(0, 0, width, height);
            canvas.paint(graphics);
        } finally { graphics.dispose(); }
        Map<String, Long> after = snapshot();
        requireLeg(before, after, Boolean.getBoolean(ENABLE), 1);
        FrameReadback result = FrameReadback.fromArgb(width, height,
            image.getRGB(0, 0, width, height, null, 0, width));
        if (result.distinctPixels() < 2) throw new IllegalStateException("blank canvas cannot certify parity");
        return result;
    }
    void requireLeg(Map<String, Long> before, Map<String, Long> after, boolean enabled, int samples) {
        long frames = delta(before, after, "completedFrames");
        long queries = delta(before, after, "queries"), hits = delta(before, after, "hits");
        if (after.getOrDefault("active", 0L) != 1L || frames < samples || queries <= 0L
            || after.getOrDefault("failures", 0L) != 0L || after.getOrDefault("glErrors", 0L) != 0L
            || after.getOrDefault("shadowMismatches", 0L) != 0L || (enabled ? hits <= 0 : hits != 0)) {
            throw new IllegalStateException("narrow hook not exercised or failed: enabled=" + enabled
                + " measuredFrames=" + frames + " measuredQueries=" + queries + " measuredHits=" + hits
                + " stats=" + after);
        }
    }
    static long delta(Map<String, Long> before, Map<String, Long> after, String key) {
        return after.getOrDefault(key, 0L) - before.getOrDefault(key, 0L);
    }
    private static void requireEdt() {
        if (!EventQueue.isDispatchThread()) throw new IllegalStateException("canvas control requires EDT");
    }
    @Override public void close() {
        requireEdt();
        try {
            if (drawable != null) {
                removeListener.invoke(drawable, listener);
                drawable = null;
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("display counter removal failed", failure);
        } finally {
            if (previous == null) System.clearProperty(ENABLE); else System.setProperty(ENABLE, previous);
        }
    }
}
