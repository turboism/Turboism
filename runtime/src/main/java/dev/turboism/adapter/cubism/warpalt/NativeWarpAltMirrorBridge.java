package dev.turboism.adapter.cubism.warpalt;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Fail-closed static entrypoint invoked from the exact Warp drag-tick hook.
 *
 * <p>For every dispatch of {@code temporaryHandler.a.b(GVector2, aG)} the injected
 * call passes the handler instance, the drag position and the modifier event. The
 * bridge acts only when all of the following hold: the reviewed hook is installed
 * and enabled, at least one plugin participates, the handler's binder is the Warp
 * binder, a grid point is selected, and Alt is held. Alt alone mirrors across the
 * vertical grid axis; Alt+Shift mirrors across the horizontal grid axis. The
 * mirrored counterpart moves by the tick displacement with the mirrored component
 * negated, so the native move and deformation loop that follows in the same
 * dispatch commits both points into the gesture's own undo group.</p>
 *
 * <p>Every host interaction is reflective: the bridge class is loaded on the agent
 * classpath while the host classes live on the application class loader, and no
 * compiled dependency in either direction may exist. Any failure is swallowed into
 * a diagnostic; it must never reach the host call site.</p>
 */
public final class NativeWarpAltMirrorBridge {
    private static final String WARP_BINDER_CLASS =
        "com.live2d.cubism.view.context.temporaryHandler.warp.WarpBinder";

    private static final AtomicReference<Binding> INSTALLED = new AtomicReference<>();
    /** Armed mirror axis published by the plugin: 0=off, 1=vertical, 2=horizontal. */
    private static final java.util.concurrent.atomic.AtomicInteger ARMED_AXIS =
        new java.util.concurrent.atomic.AtomicInteger(0);
    /**
     * Live Ctrl state published by the plugin's AWT listener. Native semantics:
     * Ctrl+drag moves the control point itself without deforming the child shapes,
     * so while Ctrl is held the mirror must stay out of the way.
     */
    private static final AtomicBoolean LIVE_CTRL = new AtomicBoolean();
    /**
     * The reviewed warp binder type. Package-private mutable only so tests can point
     * the recognition at stub classes; production never changes it.
     */
    private static final AtomicReference<String> BINDER_CLASS_NAME =
        new AtomicReference<>(WARP_BINDER_CLASS);
    private static final AtomicBoolean MOVE_APPLIED_REPORTED = new AtomicBoolean();
    private static final AtomicLong LAST_THROTTLE = new AtomicLong();
    private static final java.util.Set<String> REPORTED_SKIPS =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final Consumer<String> DEFAULT_DIAGNOSTIC = ignored -> { };
    private static final AtomicReference<Consumer<String>> DIAGNOSTIC =
        new AtomicReference<>(DEFAULT_DIAGNOSTIC);
    private static final RuntimeWarpAltMirrorParticipation PARTICIPATION =
        new RuntimeWarpAltMirrorParticipation();

    private record Binding(boolean enabled) { }

    private NativeWarpAltMirrorBridge() { }

    /** Routes bridge markers into the installer log; resets to a no-op on uninstall. */
    public static void diagnostics(final Consumer<String> sink) {
        DIAGNOSTIC.set(sink == null ? DEFAULT_DIAGNOSTIC : sink);
    }

    /** Installs an enabled bridge; the participation registry survives re-installation. */
    public static void install() {
        install(true);
    }

    /** Installs a bridge with an explicit enabled-state policy. */
    public static void install(final boolean enabled) {
        if (!INSTALLED.compareAndSet(null, new Binding(enabled))) {
            throw new IllegalStateException("warp alt mirror bridge is already installed");
        }
    }

    /** Revokes the bridge and resets session-scoped state. */
    public static void uninstall() {
        INSTALLED.set(null);
        MOVE_APPLIED_REPORTED.set(false);
        PARTICIPATION.resetSession();
        DIAGNOSTIC.set(DEFAULT_DIAGNOSTIC);
        BINDER_CLASS_NAME.set(WARP_BINDER_CLASS);
        REPORTED_SKIPS.clear();
        LAST_THROTTLE.set(0);
        ARMED_AXIS.set(0);
        LIVE_CTRL.set(false);
    }

    /** Test seam: redirects binder recognition to a stub class name. */
    static void setBinderClassNameForTesting(final String name) {
        BINDER_CLASS_NAME.set(name);
    }

    /** Test seam: restores the reviewed binder class name. */
    static void resetBinderClassName() {
        BINDER_CLASS_NAME.set(WARP_BINDER_CLASS);
    }

    /**
     * Publishes the armed mirror axis toggled by the plugin (0=off, 1=vertical,
     * 2=horizontal). While armed, every committed Warp control-point drag is
     * mirrored across the armed grid axis regardless of keyboard modifiers — the
     * native editor consumes Alt+drag before control-point drags start, so the
     * axis cannot depend on live modifiers.
     */
    public static void setArmedAxis(final int axis) {
        if (axis < 0 || axis > 2) {
            throw new IllegalArgumentException("axis must be 0 (off), 1 (vertical) or 2 (horizontal)");
        }
        ARMED_AXIS.set(axis);
    }

    /**
     * Publishes the live Ctrl state tracked by the plugin's AWT listener. While
     * Ctrl is held the native point drag is self-only (no content deformation),
     * so the mirror deliberately does not apply.
     */
    public static void setLiveCtrlDown(final boolean down) {
        LIVE_CTRL.set(down);
    }

    /** @return the plugin-facing participation registry owned by this bridge. */
    public static RuntimeWarpAltMirrorParticipation moveParticipation() {
        return PARTICIPATION;
    }

    /** @return whether the bridge is installed, enabled, and mirrored at least once. */
    public static boolean active() {
        final Binding binding = INSTALLED.get();
        return binding != null && binding.enabled();
    }

    /**
     * Point-write entry injected at the head of the converged doc-level write
     * ({@code WarpPointRef.moveToOnLocal(GVector2, float)}). Mirrors the tick
     * displacement onto the axis counterpart of the moving point by writing the
     * same positions array, so the mirrored motion lands inside the gesture's own
     * undo envelope with a live preview and no second history entry.
     */
    public static void mirrorPointMove(final Object ref, final Object target, final float weight) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled() || ref == null || target == null) {
                return;
            }
            if (!PARTICIPATION.hasParticipants()) {
                return;
            }
            final int axis = ARMED_AXIS.get();
            if (axis == 0) {
                return;
            }
            if (LIVE_CTRL.get()) {
                // Native Ctrl semantics: self-only adjustment, never mirrored.
                return;
            }
            final boolean shift = axis == 2;
            // a() returns _index; h() returns the step field which is 0 in the
            // level-2 deformer-edit flow, so the row width is derived from the
            // positions array instead (square grids).
            final int index = invokeInt(ref, "a");
            if (index < 0) {
                return;
            }
            final Object gridArray = invoke(ref, "g", new Class<?>[0]);
            if (!(gridArray instanceof float[] positions)) {
                return;
            }
            final int total = positions.length / 2;
            final int width = (int) Math.round(Math.sqrt(total));
            if (width <= 0 || width * width != total) {
                skipOnce("POINT_MOVE_NON_SQUARE total=" + total);
                return;
            }
            final int step = width;
            final int height = width;
            final int row = index / step;
            final int column = index % step;
            final int counterpartRow = shift ? height - 1 - row : row;
            final int counterpartColumn = shift ? column : step - 1 - column;
            final int counterpart = counterpartRow * step + counterpartColumn;
            if (counterpart == index
                || counterpart * 2 + 1 >= positions.length
                || index * 2 + 1 >= positions.length) {
                return;
            }
            final float targetX = invokeFloat(target, "getX");
            final float targetY = invokeFloat(target, "getY");
            final float dx = targetX - positions[index * 2];
            final float dy = targetY - positions[index * 2 + 1];
            if (Math.abs(dx) <= AltAxisMirrorMath.MOVE_EPSILON
                && Math.abs(dy) <= AltAxisMirrorMath.MOVE_EPSILON) {
                return;
            }
            if (shift) {
                positions[counterpart * 2] += weight * dx;
                positions[counterpart * 2 + 1] -= weight * dy;
            } else {
                positions[counterpart * 2] -= weight * dx;
                positions[counterpart * 2 + 1] += weight * dy;
            }
            if (MOVE_APPLIED_REPORTED.compareAndSet(false, true)) {
                diagnostic("MIRROR_APPLIED axis=" + (shift ? "horizontal" : "vertical")
                    + " step=" + step);
            }
        } catch (Throwable failure) {
            diagnostic("POINT_MOVE_MIRROR_FAILED reason=" + failure.getClass().getName());
        }
    }

    /**
     * Drag-tick entry injected at the head of the reviewed host method.
     *
     * @param handler the temporary handler instance dispatching the drag
     * @param pos the current drag position in view space
     * @param event the modifier-carrying action event
     */
    public static void mirrorWarpDragMove(final Object handler, final Object pos, final Object event) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled() || handler == null || pos == null || event == null) {
                return;
            }
            if (!PARTICIPATION.hasParticipants()) {
                skipOnce("NO_PARTICIPANT");
                return;
            }
            final Object binder = warpBinder(handler);
            if (binder == null) {
                skipOnce("NOT_WARP_HANDLER actual=" + handler.getClass().getName());
                return;
            }
            final boolean alt = invokeBoolean(event, "aA");
            if (!alt) {
                skipOnce("NO_ALT");
                return;
            }
            final boolean shift = invokeBoolean(event, "aB");
            final Integer selected = invokeInteger(binder, "getSelectedPointIndex");
            if (selected == null) {
                skipOnce("NO_SELECTED_POINT");
                return;
            }
            mirrorCounterpart(binder, selected, pos, shift);
        } catch (Throwable failure) {
            diagnostic("TICK_FAILED reason=" + failure.getClass().getName());
        }
    }

    /**
     * Moves the axis counterpart of the selected grid point by the mirrored tick
     * displacement, in place, before the native move commits the dragged point.
     */
    private static void mirrorCounterpart(
        final Object binder,
        final int selectedIndex,
        final Object pos,
        final boolean shift
    ) throws ReflectiveOperationException {
        final int width = invokeInt(binder, "rowPointSize");
        final int height = invokeInt(binder, "columnPointSize");
        if (width <= 0 || height <= 0) {
            return;
        }
        final Object indexPair = invoke(binder, "toGridIndex", new Class<?>[]{int.class}, selectedIndex);
        if (indexPair == null) {
            return;
        }
        final int row = invokeInt(indexPair, "getFirst");
        final int column = invokeInt(indexPair, "getSecond");
        final int counterpartRow = shift ? height - 1 - row : row;
        final int counterpartColumn = shift ? column : width - 1 - column;
        if (counterpartRow == row && counterpartColumn == column) {
            return;
        }
        final Object grid = invoke(binder, "getGridPoints", new Class<?>[0]);
        if (!(grid instanceof List<?> rows) || rows.size() != height) {
            return;
        }
        if (!(rows.get(row) instanceof List<?> sourceRow) || sourceRow.size() != width) {
            return;
        }
        if (!(rows.get(counterpartRow) instanceof List<?> counterpartRowList)
            || counterpartRowList.size() != width) {
            return;
        }
        final Object dragged = sourceRow.get(column);
        final Object counterpart = counterpartRowList.get(counterpartColumn);
        if (dragged == null || counterpart == null) {
            return;
        }
        final float positionX = invokeFloat(pos, "getX");
        final float positionY = invokeFloat(pos, "getY");
        final float draggedX = invokeFloat(dragged, "getX");
        final float draggedY = invokeFloat(dragged, "getY");
        final float dx = positionX - draggedX;
        final float dy = positionY - draggedY;
        if (Math.abs(dx) <= AltAxisMirrorMath.MOVE_EPSILON
            && Math.abs(dy) <= AltAxisMirrorMath.MOVE_EPSILON) {
            return;
        }
        final float counterpartX = invokeFloat(counterpart, "getX");
        final float counterpartY = invokeFloat(counterpart, "getY");
        final float targetX = shift ? counterpartX + dx : counterpartX - dx;
        final float targetY = shift ? counterpartY - dy : counterpartY + dy;
        invoke(counterpart, "setX", new Class<?>[]{float.class}, targetX);
        invoke(counterpart, "setY", new Class<?>[]{float.class}, targetY);
        if (MOVE_APPLIED_REPORTED.compareAndSet(false, true)) {
            diagnostic("MIRROR_APPLIED axis=" + (shift ? "horizontal" : "vertical"));
        }
    }

    /** Resolves the warp binder behind a temporary handler, or null for other handlers. */
    private static Object warpBinder(final Object handler) throws ReflectiveOperationException {
        final Object binder = invoke(handler, "a", new Class<?>[0]);
        if (binder == null || !BINDER_CLASS_NAME.get().equals(binder.getClass().getName())) {
            return null;
        }
        return binder;
    }

    private static Object invoke(
        final Object target,
        final String name,
        final Class<?>[] parameterTypes,
        final Object... args
    ) throws ReflectiveOperationException {
        final Method method = target.getClass().getMethod(name, parameterTypes);
        return method.invoke(target, args);
    }

    private static boolean invokeBoolean(final Object target, final String name)
        throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Boolean enabled && enabled;
    }

    private static int invokeInt(final Object target, final String name)
        throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static float invokeFloat(final Object target, final String name)
        throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Number number ? number.floatValue() : Float.NaN;
    }

    private static Integer invokeInteger(final Object target, final String name)
        throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Integer number ? number : null;
    }

    /**
     * Route diagnostics: injected at the modeling bbox/selection action heads. These
     * answer where an Alt+drag disappears: press hit-test, drag dispatch, the
     * selected-point move, or the doc-level point write.
     */
    public static void diagPressHit(final Object event) {
        if (REPORTED_SKIPS.add("route:pressHit")) {
            diagEventModifiers(event, "PRESS_HIT");
        }
    }

    public static void diagDragDispatch(final Object event) {
        if (REPORTED_SKIPS.add("route:dragDispatch")) {
            diagEventModifiers(event, "DRAG_DISPATCH");
        }
        diagEventModifiersThrottled(event, "DRAG_DISPATCH_TICK");
    }

    public static void diagMoveSelected(final Object event) {
        diagEventModifiers(event, "MOVE_SELECTED");
    }

    /** Route stage marker with the event's modifier snapshot. */
    public static void diagRoute(final Object stage, final Object event) {
        try {
            diagEventModifiers(event, stage instanceof String name ? name : "ROUTE");
        } catch (Throwable failure) {
            diagnostic("ROUTE_FAILED reason=" + failure.getClass().getName());
        }
    }

    public static void diagPointMove(final Object ref) {
        try {
            if (ref == null) return;
            final Object index = invoke(ref, "h", new Class<?>[0]);
            final Object positions = invoke(ref, "g", new Class<?>[0]);
            final int idx = index instanceof Number number ? number.intValue() : -1;
            String detail = "idx=" + idx;
            if (positions instanceof float[] xy && idx >= 0 && idx * 2 + 1 < xy.length) {
                detail += " pos=" + xy[idx * 2] + "," + xy[idx * 2 + 1];
            }
            diagnostic("POINT_MOVE " + detail + " class=" + ref.getClass().getName());
        } catch (Throwable failure) {
            diagnostic("POINT_MOVE_FAILED reason=" + failure.getClass().getName());
        }
    }

    private static void diagEventModifiers(final Object event, final String stage) {
        try {
            if (event == null) {
                diagnostic(stage + " event=null");
                return;
            }
            final boolean alt = invokeBoolean(event, "aA");
            final boolean shift = invokeBoolean(event, "aB");
            final boolean ctrl = invokeBoolean(event, "az");
            diagnostic(stage + " alt=" + alt + " shift=" + shift + " ctrl=" + ctrl);
        } catch (Throwable failure) {
            diagnostic(stage + " event-read-failed " + failure.getClass().getName());
        }
    }

    private static void diagEventModifiersThrottled(final Object event, final String stage) {
        try {
            final long now = System.currentTimeMillis();
            if (now - LAST_THROTTLE.get() < 2_000L) return;
            LAST_THROTTLE.set(now);
            diagEventModifiers(event, stage);
        } catch (Throwable ignored) {
            // never reach the host call site
        }
    }

    private static void skipOnce(final String reason) {
        if (REPORTED_SKIPS.add(reason)) {
            diagnostic("TICK_SKIP reason=" + reason);
        }
    }

    private static void diagnostic(final String stage) {
        try {
            DIAGNOSTIC.get().accept("WARP_ALT_MIRROR_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            // Diagnostics must never reach the host call site.
        }
    }

    /** Shared mirror math constants for the warp axis bridge. */
    static final class AltAxisMirrorMath {
        /** Displacement below this magnitude is treated as "did not move". */
        static final float MOVE_EPSILON = 1.0e-3f;

        private AltAxisMirrorMath() { }
    }
}
