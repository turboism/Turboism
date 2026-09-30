package dev.turboism.adapter.cubism.optimization.composite;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.util.Map;
import java.util.Properties;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies the canvas-composite bridge's slot lifecycle and the two redundancy
 * rules on a synthetic {@code com/jogamp/opengl/awt/GLJPanel}: the paint
 * consult elides only repaints whose subtree carries a visible GLJPanel, and
 * the fill consult elides only panels fully covered by a visible opaque
 * GLJPanel (where the fill is provably invisible overdraw). All checks are
 * pure Swing geometry — no native state.
 */
public class CanvasCompositeElisionBridgeTest {

    private static final String GLPANEL = "com/jogamp/opengl/awt/GLJPanel";

    private static final class Loader extends ClassLoader {
        Loader() {
            super(CanvasCompositeElisionBridgeTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    /** Synthetic {@code GLJPanel extends JComponent} (no GL needed for geometry). */
    private static byte[] glPanelStub() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, GLPANEL, null, "javax/swing/JComponent", null);
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "javax/swing/JComponent", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private Loader loader;
    private CanvasCompositeElisionBridge bridge;
    private JComponent glPanel;

    private void setUp() throws Exception {
        loader = new Loader();
        Class<?> glType = loader.define(GLPANEL, glPanelStub());
        bridge = new CanvasCompositeElisionBridge(loader);
        bridge.install();
        glPanel = (JComponent) glType.getDeclaredConstructor().newInstance();
        glPanel.setOpaque(true);
    }

    @AfterEach
    void tearDown() {
        if (bridge != null) bridge.close();
        final Properties properties = System.getProperties();
        properties.remove(CanvasCompositeElisionBridge.PAINT_PROPERTY);
        properties.remove(CanvasCompositeElisionBridge.FILL_PROPERTY);
        properties.remove(CanvasCompositeElisionBridge.GATE_PROPERTY);
        properties.remove(CanvasCompositeElisionBridge.STATS_PROPERTY);
    }

    private void arm(final boolean value) {
        ((Consumer<Boolean>) System.getProperties().get(CanvasCompositeElisionBridge.GATE_PROPERTY)).accept(value);
    }

    private Predicate<Object> paintSlot() {
        return (Predicate<Object>) System.getProperties().get(CanvasCompositeElisionBridge.PAINT_PROPERTY);
    }

    private BiPredicate<Object, Object> fillSlot() {
        return (BiPredicate<Object, Object>) System.getProperties().get(CanvasCompositeElisionBridge.FILL_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> stats() {
        return ((Supplier<Map<String, Long>>) System.getProperties().get(CanvasCompositeElisionBridge.STATS_PROPERTY))
                .get();
    }

    /** A panel holding {@code glPanel} as a covering child. */
    private JPanel coveredPanel(int panelW, int panelH, int childW, int childH) {
        JPanel panel = new JPanel(null);
        panel.setSize(panelW, panelH);
        glPanel.setBounds(0, 0, childW, childH);
        panel.add(glPanel);
        return panel;
    }

    @Test
    void installOccupiesAndCloseClearsAllSlots() throws Exception {
        setUp();
        final Properties properties = System.getProperties();
        assertTrue(properties.get(CanvasCompositeElisionBridge.PAINT_PROPERTY) instanceof Predicate);
        assertTrue(properties.get(CanvasCompositeElisionBridge.FILL_PROPERTY) instanceof BiPredicate);
        assertTrue(properties.get(CanvasCompositeElisionBridge.GATE_PROPERTY) instanceof Consumer);
        assertTrue(properties.get(CanvasCompositeElisionBridge.STATS_PROPERTY) instanceof Supplier);
        assertThrows(IllegalStateException.class, bridge::install);
        bridge.close();
        bridge = null;
        assertNull(properties.get(CanvasCompositeElisionBridge.PAINT_PROPERTY));
        assertNull(properties.get(CanvasCompositeElisionBridge.FILL_PROPERTY));
        assertNull(properties.get(CanvasCompositeElisionBridge.GATE_PROPERTY));
        assertNull(properties.get(CanvasCompositeElisionBridge.STATS_PROPERTY));
    }

    @Test
    void disarmedConsultsPassAndCount() throws Exception {
        setUp();
        assertFalse(paintSlot().test(coveredPanel(100, 100, 100, 100)));
        assertFalse(fillSlot().test(null, coveredPanel(100, 100, 100, 100)));
        Map<String, Long> snapshot = stats();
        assertEquals(1L, snapshot.get("paintCalls"));
        assertEquals(1L, snapshot.get("paintPassed"));
        assertEquals(0L, snapshot.get("paintElided"));
        assertEquals(1L, snapshot.get("fillCalls"));
        assertEquals(1L, snapshot.get("fillPassed"));
        assertEquals(0L, snapshot.get("fillElided"));
        assertEquals(0L, snapshot.get("armed"));
    }

    @Test
    void paintElidesOnlySubtreesCarryingGLPanel() throws Exception {
        setUp();
        arm(true);
        assertTrue(paintSlot().test(glPanel), "the canvas itself repaints directly");
        assertTrue(
                paintSlot().test(coveredPanel(100, 100, 100, 100)),
                "a dirty root containing the GLJPanel elides the back buffer");
        JPanel plain = new JPanel();
        plain.add(new JPanel());
        assertFalse(paintSlot().test(plain), "a plain subtree keeps the buffered path");
        assertFalse(paintSlot().test("not-a-component"));
        glPanel.setVisible(false);
        assertFalse(
                paintSlot().test(coveredPanel(100, 100, 100, 100)),
                "an invisible GLJPanel subtree keeps the buffered path");
        assertEquals(2L, stats().get("paintElided"));
        assertEquals(3L, stats().get("paintPassed"));
    }

    @Test
    void fillElidesOnlyFullyCoveredOpaquePanels() throws Exception {
        setUp();
        arm(true);
        JPanel covered = coveredPanel(100, 100, 100, 100);
        covered.setOpaque(true);
        assertTrue(
                fillSlot().test(null, covered), "an opaque panel fully covered by an opaque GLJPanel skips its fill");
        assertEquals(1L, stats().get("fillElided"));

        // Partial coverage: the fill would show → keep it.
        JPanel partial = coveredPanel(100, 100, 50, 50);
        partial.setOpaque(true);
        assertFalse(fillSlot().test(null, partial));

        // Non-opaque panel: FlatPanelUI.update does not fill → no benefit.
        JPanel transparent = coveredPanel(100, 100, 100, 100);
        transparent.setOpaque(false);
        assertFalse(fillSlot().test(null, transparent));

        // Non-opaque GLJPanel does not prove invisible overdraw.
        glPanel.setOpaque(false);
        JPanel translucentCover = coveredPanel(100, 100, 100, 100);
        translucentCover.setOpaque(true);
        assertFalse(fillSlot().test(null, translucentCover));

        glPanel.setOpaque(true);
        glPanel.setVisible(false);
        JPanel hiddenCover = coveredPanel(100, 100, 100, 100);
        hiddenCover.setOpaque(true);
        assertFalse(fillSlot().test(null, hiddenCover));
        assertEquals(4L, stats().get("fillPassed"));
        assertEquals(0L, stats().get("observerFailures"));
    }

    @Test
    void fillElidesThroughIntermediateContainers() throws Exception {
        setUp();
        arm(true);
        JPanel inner = coveredPanel(100, 100, 100, 100);
        inner.setOpaque(true);
        JPanel outer = new JPanel(null);
        outer.setOpaque(true);
        outer.setSize(100, 100);
        inner.setBounds(0, 0, 100, 100);
        outer.add(inner);
        assertTrue(fillSlot().test(null, outer), "outer fill is invisible when an intermediate panel is GL-covered");
        assertEquals(1L, stats().get("fillElided"));
    }

    @Test
    void nullAndForeignArgumentsPass() throws Exception {
        setUp();
        arm(true);
        assertFalse(fillSlot().test(null, null));
        assertFalse(fillSlot().test(null, "foreign"));
        assertFalse(paintSlot().test(null));
        Map<String, Long> snapshot = stats();
        assertEquals(2L, snapshot.get("fillPassed"));
        assertEquals(1L, snapshot.get("paintPassed"));
        assertEquals(0L, snapshot.get("observerFailures"), "null/foreign arguments are passes, not observer failures");
    }
}
