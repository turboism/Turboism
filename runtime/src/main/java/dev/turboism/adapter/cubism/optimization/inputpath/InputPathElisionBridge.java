package dev.turboism.adapter.cubism.optimization.inputpath;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Loader-neutral bridge for the test-only input-path elision experiment.
 *
 * <p>The injected {@code CWidget} bytecode consults JDK functional-interface
 * values stored in {@link System#getProperties()}, so no Turboism class is
 * referenced by the rewritten method. The consults resolve
 * {@code CWidget.getJComponent()} and {@code CCursor.getJCursor()} once through
 * {@link MethodHandles#publicLookup} (both members are {@code public}; the host
 * JAR lives in the unnamed module) and defer the decision to the reviewed
 * redundancy rules:</p>
 *
 * <ul>
 *   <li>focus: elide only when the {@link KeyboardFocusManager}'s focus owner
 *   IS the component, its focused window IS the component's window, and that
 *   window {@code isActive()} — requests during activation transitions must
 *   still reach the original focus forwarder;</li>
 *   <li>cursor: elide when the component is showing, has an explicitly set
 *   cursor ({@code isCursorSet()}) and that cursor is the identical
 *   {@link Cursor} instance the call would assign — {@code Cursor} does not
 *   override {@code equals}, so identity is the whole equality contract and the
 *   skipped field write and native update are no-ops.</li>
 * </ul>
 *
 * <p>A disarmed leg still evaluates the consult and counts {@code passed}, so
 * OFF legs carry the same observer overhead as ON legs.</p>
 */
public final class InputPathElisionBridge implements AutoCloseable {

    /** Production opt-in switch, persisted by the managed launcher. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.inputPathElision";
    /** Slot for the {@code Predicate<Object>} focus consult. */
    public static final String FOCUS_PROPERTY = "turboism.input-path.focus";
    /** Slot for the {@code BiPredicate<Object,Object>} cursor consult. */
    public static final String CURSOR_PROPERTY = "turboism.input-path.cursor";
    /** Slot for the {@code Consumer<Boolean>} arm/disarm control. */
    public static final String GATE_PROPERTY = "turboism.input-path.gate";
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.input-path.stats";

    private final MethodHandle componentHandle;
    private final MethodHandle cursorHandle;
    private final AtomicBoolean active = new AtomicBoolean();
    private final Predicate<Object> focus = this::testFocus;
    private final BiPredicate<Object, Object> cursor = this::testCursor;
    private final Consumer<Boolean> gate = this::setArmed;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private volatile boolean armed;
    private Properties installedProperties;
    private long focusCalls, focusElided, focusPassed;
    private long cursorCalls, cursorElided, cursorPassed;
    private long observerFailures;

    /**
     * Resolves the reviewed dependency handles on the host loader.
     *
     * @throws ReflectiveOperationException when any reviewed member is absent
     */
    public InputPathElisionBridge(final ClassLoader loader)
            throws ReflectiveOperationException {
        final Class<?> widget = Class.forName(
            InputPathElisionTarget.OWNER.replace('/', '.'), false, loader);
        componentHandle = MethodHandles.publicLookup().findVirtual(widget,
            InputPathElisionTarget.COMPONENT_METHOD,
            MethodType.methodType(JComponent.class));
        final Class<?> cursorType = Class.forName(
            InputPathElisionTarget.CURSOR_OWNER.replace('/', '.'), false, loader);
        cursorHandle = MethodHandles.publicLookup().findVirtual(cursorType,
            InputPathElisionTarget.CURSOR_ACCESSOR,
            MethodType.methodType(Cursor.class));
    }

    /**
     * The production switch; the validation flag is intentionally separate.
     * The elided input path exists only under Wine/Proton, so an unset
     * property defaults to on there and off on native Windows; an explicit
     * {@code =true}/{@code =false} always wins.
     */
    public static boolean enabledByPreference() {
        final String explicit = System.getProperty(ENABLE_PROPERTY);
        return explicit == null
            ? dev.turboism.runtime.env.ProtonEnvironment.underWineOrProton()
            : Boolean.parseBoolean(explicit);
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
        if (active.get()) throw new IllegalStateException("input path elision already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(FOCUS_PROPERTY) || properties.containsKey(CURSOR_PROPERTY)
                || properties.containsKey(GATE_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("input path elision slots occupied");
            }
            try {
                properties.put(FOCUS_PROPERTY, focus);
                properties.put(CURSOR_PROPERTY, cursor);
                properties.put(GATE_PROPERTY, gate);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
                armed = production;
            } catch (RuntimeException | Error failure) {
                properties.remove(FOCUS_PROPERTY, focus);
                properties.remove(CURSOR_PROPERTY, cursor);
                properties.remove(GATE_PROPERTY, gate);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private void setArmed(final boolean value) {
        armed = value;
    }

    /**
     * Focus consult: elide when the widget's component already owns Java focus
     * inside a focused window, so the skipped {@code requestFocus} forward —
     * and its Wine-side native query — cannot change the observable state.
     */
    private boolean testFocus(final Object widget) {
        focusCalls++;
        if (!active.get() || !armed) {
            focusPassed++;
            return false;
        }
        try {
            final boolean elide = focusAlreadyHeld((Component) componentHandle.invoke(widget));
            if (elide) focusElided++; else focusPassed++;
            return elide;
        } catch (Throwable observerFailure) {
            observerFailures++;
            focusPassed++;
            return false;
        }
    }

    /**
     * Cursor consult: elide when the component already displays the identical
     * {@link Cursor} instance the call would assign.
     */
    private boolean testCursor(final Object widget, final Object packCursor) {
        cursorCalls++;
        if (!active.get() || !armed) {
            cursorPassed++;
            return false;
        }
        try {
            final Cursor target = packCursor == null
                ? null
                : (Cursor) cursorHandle.invoke(packCursor);
            final boolean elide =
                cursorUnchanged((Component) componentHandle.invoke(widget), target);
            if (elide) cursorElided++; else cursorPassed++;
            return elide;
        } catch (Throwable observerFailure) {
            observerFailures++;
            cursorPassed++;
            return false;
        }
    }

    /**
     * Retain requests during activation/focus transitions. These are Java AWT
     * state checks, not a native focus probe; every condition must hold before
     * the component's requestFocus forwarder can be redundant.
     */
    static boolean focusAlreadyHeld(final Component component) {
        if (component == null) return false;
        final KeyboardFocusManager manager =
            KeyboardFocusManager.getCurrentKeyboardFocusManager();
        final Window window = component instanceof Window owned
            ? owned : SwingUtilities.getWindowAncestor(component);
        return window != null
            && manager.getFocusOwner() == component
            && manager.getFocusedWindow() == window
            && window.isActive();
    }

    /** Pure Java redundancy rule for the cursor forwarder; never touches native state. */
    static boolean cursorUnchanged(final Component component, final Cursor target) {
        return component != null
            && component.isShowing()
            && component.isCursorSet()
            && component.getCursor() == target;
    }

    /** Clears owned slots; outstanding consults fall back to the native path. */
    @Override public synchronized void close() {
        active.set(false);
        armed = false;
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(FOCUS_PROPERTY, focus);
            properties.remove(CURSOR_PROPERTY, cursor);
            properties.remove(GATE_PROPERTY, gate);
            properties.remove(STATS_PROPERTY, statistics);
        }
    }

    /** Work counts only; no interaction benefit is inferred from them. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("calls", focusCalls + cursorCalls);
        result.put("elided", focusElided + cursorElided);
        result.put("passed", focusPassed + cursorPassed);
        result.put("focusCalls", focusCalls);
        result.put("focusElided", focusElided);
        result.put("focusPassed", focusPassed);
        result.put("cursorCalls", cursorCalls);
        result.put("cursorElided", cursorElided);
        result.put("cursorPassed", cursorPassed);
        result.put("observerFailures", observerFailures);
        result.put("armed", armed ? 1L : 0L);
        return Map.copyOf(result);
    }
}
