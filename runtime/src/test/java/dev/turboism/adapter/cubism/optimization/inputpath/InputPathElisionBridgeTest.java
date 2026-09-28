package dev.turboism.adapter.cubism.optimization.inputpath;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.DefaultKeyboardFocusManager;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Verifies the input-path elision bridge's slot lifecycle and the redundancy
 * decisions on synthetic host classes named exactly like the reviewed owners.
 * The consults never reach native state: {@code focusAlreadyHeld} reads only
 * the Java focus manager and {@code cursorUnchanged} reads only component
 * fields, so a headless JComponent subclass can drive every decision branch
 * including activation transitions via an isolated focus manager without a native peer.
 */
public class InputPathElisionBridgeTest {

    private static final String WIDGET = "com/live2d/ui/CWidget";
    private static final String CCURSOR = "com/live2d/type/CCursor";

    private static final class Loader extends ClassLoader {
        Loader() {
            super(InputPathElisionBridgeTest.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name.replace('/', '.'), bytes, 0, bytes.length);
        }
    }

    /** A JComponent whose Java-side showing/cursor state is test-controlled. */
    private static final class FakeComponent extends JComponent {
        private boolean showing, cursorSet;
        private Cursor cursor;
        private Window parentWindow;

        @Override
        public java.awt.Container getParent() {
            return parentWindow;
        }

        @Override
        public boolean isShowing() {
            return showing;
        }

        @Override
        public boolean isCursorSet() {
            return cursorSet;
        }

        @Override
        public Cursor getCursor() {
            return cursor;
        }
    }

    /** {@code public JComponent component; public JComponent getJComponent()}. */
    private static byte[] widgetStub() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, WIDGET, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "component", "Ljavax/swing/JComponent;", null, null)
                .visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "getJComponent", "()Ljavax/swing/JComponent;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, WIDGET, "component", "Ljavax/swing/JComponent;");
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    /** {@code public Cursor jcursor; public Cursor getJCursor()}. */
    private static byte[] cursorStub() {
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        w.visit(V17, ACC_PUBLIC, CCURSOR, null, "java/lang/Object", null);
        w.visitField(ACC_PUBLIC, "jcursor", "Ljava/awt/Cursor;", null, null).visitEnd();
        MethodVisitor m = w.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        m.visitInsn(RETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        m = w.visitMethod(ACC_PUBLIC | ACC_FINAL, "getJCursor", "()Ljava/awt/Cursor;", null, null);
        m.visitCode();
        m.visitVarInsn(ALOAD, 0);
        m.visitFieldInsn(GETFIELD, CCURSOR, "jcursor", "Ljava/awt/Cursor;");
        m.visitInsn(ARETURN);
        m.visitMaxs(0, 0);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private Loader loader;
    private InputPathElisionBridge bridge;
    private Object widget;
    private FakeComponent component;
    private KeyboardFocusManager priorFocusManager;

    private void setUp() throws Exception {
        loader = new Loader();
        Class<?> widgetType = loader.define(WIDGET, widgetStub());
        loader.define(CCURSOR, cursorStub());
        bridge = new InputPathElisionBridge(loader);
        bridge.install();
        widget = widgetType.getDeclaredConstructor().newInstance();
        component = new FakeComponent();
        widgetType.getField("component").set(widget, component);
    }

    @AfterEach
    void tearDown() {
        if (priorFocusManager != null) {
            KeyboardFocusManager.setCurrentKeyboardFocusManager(priorFocusManager);
        }
        if (bridge != null) bridge.close();
        final Properties properties = System.getProperties();
        properties.remove(InputPathElisionBridge.FOCUS_PROPERTY);
        properties.remove(InputPathElisionBridge.CURSOR_PROPERTY);
        properties.remove(InputPathElisionBridge.GATE_PROPERTY);
        properties.remove(InputPathElisionBridge.STATS_PROPERTY);
    }

    private void arm(final boolean value) {
        ((Consumer<Boolean>) System.getProperties().get(InputPathElisionBridge.GATE_PROPERTY)).accept(value);
    }

    private Predicate<Object> focusSlot() {
        return (Predicate<Object>) System.getProperties().get(InputPathElisionBridge.FOCUS_PROPERTY);
    }

    private BiPredicate<Object, Object> cursorSlot() {
        return (BiPredicate<Object, Object>) System.getProperties().get(InputPathElisionBridge.CURSOR_PROPERTY);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> stats() {
        return ((Supplier<Map<String, Long>>) System.getProperties().get(InputPathElisionBridge.STATS_PROPERTY)).get();
    }

    @Test
    void installOccupiesAndCloseClearsAllSlots() throws Exception {
        setUp();
        final Properties properties = System.getProperties();
        assertTrue(properties.get(InputPathElisionBridge.FOCUS_PROPERTY) instanceof Predicate);
        assertTrue(properties.get(InputPathElisionBridge.CURSOR_PROPERTY) instanceof BiPredicate);
        assertTrue(properties.get(InputPathElisionBridge.GATE_PROPERTY) instanceof Consumer);
        assertTrue(properties.get(InputPathElisionBridge.STATS_PROPERTY) instanceof Supplier);
        assertThrows(IllegalStateException.class, bridge::install, "a second installation must not replace the slots");
        bridge.close();
        bridge = null;
        assertNull(properties.get(InputPathElisionBridge.FOCUS_PROPERTY));
        assertNull(properties.get(InputPathElisionBridge.CURSOR_PROPERTY));
        assertNull(properties.get(InputPathElisionBridge.GATE_PROPERTY));
        assertNull(properties.get(InputPathElisionBridge.STATS_PROPERTY));
    }

    @Test
    void disarmedConsultsPassAndCount() throws Exception {
        setUp();
        component.showing = true;
        component.cursorSet = true;
        component.cursor = Cursor.getDefaultCursor();
        assertFalse(focusSlot().test(widget), "disarmed focus consult must pass");
        assertFalse(cursorSlot().test(widget, null), "disarmed cursor consult must pass");
        Map<String, Long> snapshot = stats();
        assertEquals(1L, snapshot.get("focusCalls"));
        assertEquals(1L, snapshot.get("focusPassed"));
        assertEquals(0L, snapshot.get("focusElided"));
        assertEquals(1L, snapshot.get("cursorCalls"));
        assertEquals(1L, snapshot.get("cursorPassed"));
        assertEquals(0L, snapshot.get("cursorElided"));
        assertEquals(0L, snapshot.get("observerFailures"));
        assertEquals(0L, snapshot.get("armed"));
    }

    @Test
    void identicalShowingCursorElides() throws Exception {
        setUp();
        arm(true);
        final Cursor shared = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        component.showing = true;
        component.cursorSet = true;
        component.cursor = shared;
        final Class<?> cursorType = loader.loadClass(CCURSOR.replace('/', '.'));
        final Object packCursor = cursorType.getDeclaredConstructor().newInstance();
        cursorType.getField("jcursor").set(packCursor, shared);
        assertTrue(cursorSlot().test(widget, packCursor), "same Cursor instance on a showing component is redundant");
        assertEquals(1L, stats().get("cursorElided"));
    }

    @Test
    void differentOrUnsetCursorPasses() throws Exception {
        setUp();
        arm(true);
        component.showing = true;
        component.cursorSet = true;
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        final Class<?> cursorType = loader.loadClass(CCURSOR.replace('/', '.'));
        final Object other = cursorType.getDeclaredConstructor().newInstance();
        cursorType.getField("jcursor").set(other, Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        assertFalse(cursorSlot().test(widget, other), "a different Cursor instance must reach the native path");
        assertFalse(cursorSlot().test(widget, null), "a null argument never matches a set cursor");
        component.cursorSet = false;
        component.cursor = null;
        assertFalse(cursorSlot().test(widget, null), "an unset cursor field is not proven identical");
        component.cursorSet = true;
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        component.showing = false;
        assertFalse(
                cursorSlot().test(widget, pack(cursorType, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))),
                "a hidden component takes the native path per the reviewed rule");
        assertEquals(4L, stats().get("cursorPassed"));
        assertEquals(0L, stats().get("cursorElided"));
    }

    private static Object pack(Class<?> cursorType, Cursor cursor) throws Exception {
        final Object instance = cursorType.getDeclaredConstructor().newInstance();
        cursorType.getField("jcursor").set(instance, cursor);
        return instance;
    }

    /** A focus manager whose owner/focused/active state is test-controlled. */
    private static final class FakeFocusManager extends DefaultKeyboardFocusManager {
        private Component owner;
        private Window focusedWindow;
        private Window activeWindow;

        @Override
        public Component getFocusOwner() {
            return owner;
        }

        @Override
        public Window getFocusedWindow() {
            return focusedWindow;
        }

        @Override
        public Window getActiveWindow() {
            return activeWindow;
        }
    }

    /**
     * A Window instance without a peer: {@code allocateInstance} skips the
     * constructor that would fail headless, while {@code isFocused()} and
     * {@code isActive()} delegate to the (injected) KeyboardFocusManager, so
     * the reviewed focus rule can be exercised end to end.
     */
    private static Window bareWindow() {
        try {
            final Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafe.setAccessible(true);
            return (Window) ((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(Window.class);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void productionFocusRequiresExactOwnerFocusedWindowAndActiveWindow() throws Exception {
        setUp();
        bridge.close();
        bridge = new InputPathElisionBridge(loader);
        bridge.install(true); // production is armed before the first focus request
        final FakeFocusManager manager = new FakeFocusManager();
        priorFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        KeyboardFocusManager.setCurrentKeyboardFocusManager(manager);
        final Window window = bareWindow();
        final Window other = bareWindow();
        assertFalse(focusSlot().test(widget), "startup without focus must forward");
        manager.owner = component;
        manager.focusedWindow = window;
        manager.activeWindow = window;
        assertFalse(focusSlot().test(widget), "detached component must forward");
        component.parentWindow = window;
        manager.activeWindow = null;
        assertFalse(focusSlot().test(widget), "stale owner in inactive window must forward");
        manager.activeWindow = other;
        assertFalse(focusSlot().test(widget), "another active window must forward");
        manager.activeWindow = window;
        manager.focusedWindow = other;
        assertFalse(focusSlot().test(widget), "another focused window must forward");
        manager.focusedWindow = window;
        manager.owner = new FakeComponent();
        assertFalse(focusSlot().test(widget), "another focus owner must forward");
        manager.owner = component;
        assertTrue(window.isActive());
        assertTrue(focusSlot().test(widget), "only all three conditions permit elision");
        assertEquals(6L, stats().get("focusPassed"));
        assertEquals(1L, stats().get("focusElided"));
        assertEquals(0L, stats().get("observerFailures"));
    }

    @Test
    void productionInstallArmsImmediately() throws Exception {
        setUp();
        bridge.close();
        bridge = new InputPathElisionBridge(loader);
        bridge.install(true);
        component.showing = true;
        component.cursorSet = true;
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);
        final Class<?> cursorType = loader.loadClass(CCURSOR.replace('/', '.'));
        final Object packCursor = cursorType.getDeclaredConstructor().newInstance();
        cursorType.getField("jcursor").set(packCursor, component.cursor);
        assertTrue(cursorSlot().test(widget, packCursor), "production installs consult armed without the leg gate");
        assertEquals(1L, stats().get("cursorElided"));
        assertEquals(1L, stats().get("armed"));
    }

    @Test
    void missingComponentCountsObserverFailure() throws Exception {
        setUp();
        arm(true);
        widget.getClass().getField("component").set(widget, null);
        assertFalse(focusSlot().test(widget));
        assertFalse(cursorSlot().test(widget, null));
        Map<String, Long> snapshot = stats();
        assertEquals(1L, snapshot.get("focusPassed"));
        assertEquals(1L, snapshot.get("cursorPassed"));
        assertEquals(0L, snapshot.get("focusElided"));
        assertEquals(0L, snapshot.get("cursorElided"));
        assertEquals(0L, snapshot.get("observerFailures"), "a null component is a pass, not an observer failure");
    }
}
