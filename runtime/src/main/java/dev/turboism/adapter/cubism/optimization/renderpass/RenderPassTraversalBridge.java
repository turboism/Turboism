package dev.turboism.adapter.cubism.optimization.renderpass;

import java.util.Objects;
import java.util.Properties;

/**
 * Publishes pre-built debug draw labels for {@code rendering/e.b} and {@code rendering/d.b}.
 *
 * <p>The render pass invokes {@code shader/A.a} (or {@code shader/w.a} / {@code shader/y.a}) with
 * {@code "pre draw[" + i + "]"} and {@code "post draw[" + i + "]"} on every mesh draw call.
 * Publishing immutable {@code String[]} lookup tables in {@link System#getProperties()} lets the
 * woven method fetch both table references once per frame and eliminate per-draw string
 * concatenation allocations while preserving exact error messages.</p>
 */
public final class RenderPassTraversalBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived preference. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.renderPassTraversal";

    /** Property key storing the immutable pre-draw label array ({@code String[]}). */
    public static final String PRE_DRAW_LABELS_PROPERTY = "turboism.render-pass.pre-draw-labels";

    /** Property key storing the immutable post-draw label array ({@code String[]}). */
    public static final String POST_DRAW_LABELS_PROPERTY = "turboism.render-pass.post-draw-labels";

    /** Maximum pre-cached mesh draw index covered without heap concatenation. */
    public static final int LABEL_CACHE_SIZE = 4096;

    private static final String PRE_DRAW_PREFIX = "pre draw[";
    private static final String POST_DRAW_PREFIX = "post draw[";
    private static final String LABEL_SUFFIX = "]";

    private static final String[] PRE_DRAW_LABELS = buildLabels(PRE_DRAW_PREFIX);
    private static final String[] POST_DRAW_LABELS = buildLabels(POST_DRAW_PREFIX);

    private Properties installedProperties;
    private Object previousPreLabels;
    private Object previousPostLabels;
    private boolean installed;

    /**
     * Installs pre-built label tables into {@link System#getProperties()}.
     */
    public synchronized void install() {
        if (installed) {
            return;
        }
        final Properties properties = System.getProperties();
        previousPreLabels = properties.put(PRE_DRAW_LABELS_PROPERTY, PRE_DRAW_LABELS);
        previousPostLabels = properties.put(POST_DRAW_LABELS_PROPERTY, POST_DRAW_LABELS);
        installedProperties = properties;
        installed = true;
    }

    @Override
    public synchronized void close() {
        if (!installed) {
            return;
        }
        final Properties properties = installedProperties;
        if (properties != null) {
            restoreOrRemove(properties, PRE_DRAW_LABELS_PROPERTY, PRE_DRAW_LABELS, previousPreLabels);
            restoreOrRemove(properties, POST_DRAW_LABELS_PROPERTY, POST_DRAW_LABELS, previousPostLabels);
        }
        installedProperties = null;
        previousPreLabels = null;
        previousPostLabels = null;
        installed = false;
    }

    /**
     * Returns whether the pre-draw and post-draw label tables are currently published.
     *
     * @return true when installed and not yet closed
     */
    public synchronized boolean active() {
        return installed;
    }

    /**
     * Returns the cached pre-draw label for {@code index}, or formats it when outside the table.
     *
     * @param index draw call index
     * @return exact {@code "pre draw[<index>]"} label
     */
    public static String preDrawLabel(final int index) {
        return (index >= 0 && index < PRE_DRAW_LABELS.length)
                ? PRE_DRAW_LABELS[index]
                : PRE_DRAW_PREFIX + index + LABEL_SUFFIX;
    }

    /**
     * Returns the cached post-draw label for {@code index}, or formats it when outside the table.
     *
     * @param index draw call index
     * @return exact {@code "post draw[<index>]"} label
     */
    public static String postDrawLabel(final int index) {
        return (index >= 0 && index < POST_DRAW_LABELS.length)
                ? POST_DRAW_LABELS[index]
                : POST_DRAW_PREFIX + index + LABEL_SUFFIX;
    }

    private static String[] buildLabels(final String prefix) {
        final String[] labels = new String[LABEL_CACHE_SIZE];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = prefix + i + LABEL_SUFFIX;
        }
        return labels;
    }

    private static void restoreOrRemove(
            final Properties properties, final String key, final Object expectedValue, final Object previousValue) {
        final Object current = properties.get(key);
        if (current != expectedValue) {
            return;
        }
        if (previousValue == null) {
            properties.remove(key);
        } else {
            properties.put(key, Objects.requireNonNull(previousValue));
        }
    }
}
