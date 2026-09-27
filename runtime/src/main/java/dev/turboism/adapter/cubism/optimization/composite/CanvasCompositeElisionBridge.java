package dev.turboism.adapter.cubism.optimization.composite;

import java.awt.Component;
import java.awt.Container;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;

/**
 * Loader-neutral bridge for the test-only canvas-composite elision
 * experiment. The injected bytecode consults JDK functional-interface slots in
 * {@link System#getProperties()}:
 *
 * <ul>
 *   <li>{@link #PAINT_PROPERTY} — {@code Predicate<Object>} on the repaint's
 *   painting component; {@code true} makes {@code PaintManager.paint} return
 *   {@code false}, selecting the JDK's own direct-paint fallback (the
 *   component paints straight into the window graphics instead of the shared
 *   offscreen back buffer). The bridge answers {@code true} only when the
 *   painting component's subtree contains a {@code GLJPanel}, i.e. the
 *   repaint actually carries the GL readback image — other repaints keep the
 *   buffered path;</li>
 *   <li>{@link #FILL_PROPERTY} — {@code BiPredicate<Object,Object>} on
 *   {@code (graphics, component)} inside {@code FlatPanelUI.update};
 *   {@code true} skips the background fill. The bridge answers {@code true}
 *   only when the panel is opaque and fully covered by a visible opaque
 *   {@code GLJPanel} subtree — the {@code GLJPanel} paints an opaque
 *   ({@code comp=3}) image over its entire bounds, so the fill is provably
 *   invisible overdraw.</li>
 * </ul>
 *
 * <p>A disarmed leg still evaluates the consult and counts {@code passed} so
 * OFF legs carry the same slot overhead; the subtree walk runs only when
 * armed.</p>
 */
public final class CanvasCompositeElisionBridge implements AutoCloseable {

    /** Slot for the {@code Predicate<Object>} paint-path consult. */
    public static final String PAINT_PROPERTY = "turboism.canvas-composite.paint";
    /** Slot for the {@code BiPredicate<Object,Object>} fill consult. */
    public static final String FILL_PROPERTY = "turboism.canvas-composite.fill";
    /** Slot for the {@code Consumer<Boolean>} arm/disarm control. */
    public static final String GATE_PROPERTY = "turboism.canvas-composite.gate";
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.canvas-composite.stats";

    private final Class<?> glPanel;
    private final AtomicBoolean active = new AtomicBoolean();
    private final Predicate<Object> paint = this::testPaint;
    private final BiPredicate<Object, Object> fill = this::testFill;
    private final Consumer<Boolean> gate = this::setArmed;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private volatile boolean armed;
    private Properties installedProperties;
    private long paintCalls, paintElided, paintPassed;
    private long fillCalls, fillElided, fillPassed;
    private long observerFailures;

    /**
     * Resolves the marker class on the host loader.
     *
     * @throws ReflectiveOperationException when the reviewed class is absent
     */
    public CanvasCompositeElisionBridge(final ClassLoader loader)
            throws ReflectiveOperationException {
        glPanel = Class.forName(
            CanvasCompositeElisionTarget.GL_PANEL_OWNER.replace('/', '.'), false, loader);
    }

    /** Occupies the consult/gate/stats slots; refuses to replace another installation. */
    public synchronized void install() {
        install(false);
    }

    /**
     * Occupies the slots; production installs arm the consult immediately while
     * validation installs stay disarmed until the workload's leg gate flips.
     */
    public synchronized void install(final boolean production) {
        if (active.get()) throw new IllegalStateException("canvas composite elision already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(PAINT_PROPERTY) || properties.containsKey(FILL_PROPERTY)
                || properties.containsKey(GATE_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("canvas composite elision slots occupied");
            }
            try {
                properties.put(PAINT_PROPERTY, paint);
                properties.put(FILL_PROPERTY, fill);
                properties.put(GATE_PROPERTY, gate);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
                armed = production;
            } catch (RuntimeException | Error failure) {
                properties.remove(PAINT_PROPERTY, paint);
                properties.remove(FILL_PROPERTY, fill);
                properties.remove(GATE_PROPERTY, gate);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private void setArmed(final boolean value) {
        armed = value;
    }

    /** Paint-path consult: direct-paint only when the repaint carries the GL canvas. */
    private boolean testPaint(final Object paintingComponent) {
        paintCalls++;
        if (!active.get() || !armed) {
            paintPassed++;
            return false;
        }
        try {
            final boolean elide = paintingComponent instanceof Component comp
                && containsGLPanel(comp);
            if (elide) paintElided++; else paintPassed++;
            return elide;
        } catch (Throwable observerFailure) {
            observerFailures++;
            paintPassed++;
            return false;
        }
    }

    /** Fill consult: skip the background fill only under proven opaque GL coverage. */
    private boolean testFill(final Object graphics, final Object component) {
        fillCalls++;
        if (!active.get() || !armed) {
            fillPassed++;
            return false;
        }
        try {
            final boolean elide = component instanceof JComponent panel
                && panel.isOpaque() && coveredByOpaqueGLPanel(panel);
            if (elide) fillElided++; else fillPassed++;
            return elide;
        } catch (Throwable observerFailure) {
            observerFailures++;
            fillPassed++;
            return false;
        }
    }

    /** True when the component is or contains a visible GLJPanel anywhere below. */
    private boolean containsGLPanel(final Component component) {
        if (glPanel.isInstance(component) && component.isVisible()) {
            return true;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsGLPanel(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the component's full bounds are covered by a visible opaque
     * {@code GLJPanel} subtree — the GL image writes opaque pixels over the
     * whole area, so any background fill beneath it cannot be observed.
     */
    private boolean coveredByOpaqueGLPanel(final Component component) {
        if (!(component instanceof Container container)) {
            return false;
        }
        final int width = component.getWidth(), height = component.getHeight();
        for (Component child : container.getComponents()) {
            if (!child.isVisible()
                || child.getX() > 0 || child.getY() > 0
                || child.getX() + child.getWidth() < width
                || child.getY() + child.getHeight() < height) {
                continue;
            }
            if (glPanel.isInstance(child) && child instanceof JComponent jc && jc.isOpaque()) {
                return true;
            }
            if (coveredByOpaqueGLPanel(child)) {
                return true;
            }
        }
        return false;
    }

    /** Clears owned slots; outstanding consults fall back to the native path. */
    @Override public synchronized void close() {
        active.set(false);
        armed = false;
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(PAINT_PROPERTY, paint);
            properties.remove(FILL_PROPERTY, fill);
            properties.remove(GATE_PROPERTY, gate);
            properties.remove(STATS_PROPERTY, statistics);
        }
    }

    /** Work counts only; no interaction benefit is inferred from them. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("calls", paintCalls + fillCalls);
        result.put("elided", paintElided + fillElided);
        result.put("passed", paintPassed + fillPassed);
        result.put("paintCalls", paintCalls);
        result.put("paintElided", paintElided);
        result.put("paintPassed", paintPassed);
        result.put("fillCalls", fillCalls);
        result.put("fillElided", fillElided);
        result.put("fillPassed", fillPassed);
        result.put("observerFailures", observerFailures);
        result.put("armed", armed ? 1L : 0L);
        return Map.copyOf(result);
    }
}
