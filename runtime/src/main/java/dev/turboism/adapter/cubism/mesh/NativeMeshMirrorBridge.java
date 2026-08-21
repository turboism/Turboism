package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Fail-closed static entrypoint used only by the exact mesh-mirror transformer. */
public final class NativeMeshMirrorBridge {
    private static final AtomicReference<Binding> INSTALLED = new AtomicReference<>();
    private static final AtomicReference<Object> CURRENT_PANEL = new AtomicReference<>();
    private static final AtomicReference<Object> CURRENT_CONTEXT = new AtomicReference<>();
    /**
     * The host builds the mirror widget exactly once, from a static initializer, well before
     * the runtime can bind. One slot is therefore enough: it holds the references that single
     * callback delivered so binding can attach without ever searching the host UI.
     */
    private static final AtomicReference<PendingAttach> PENDING = new AtomicReference<>();
    private static final AtomicBoolean CONTROL_ATTACHED = new AtomicBoolean();
    private static final Consumer<String> DEFAULT_DIAGNOSTIC = System.err::println;
    private static final AtomicReference<Consumer<String>> DIAGNOSTIC =
        new AtomicReference<>(DEFAULT_DIAGNOSTIC);

    private NativeMeshMirrorBridge() { }

    /** Routes host-path markers into the installer log; resets to stderr on uninstall. */
    public static void diagnostics(final Consumer<String> sink) {
        DIAGNOSTIC.set(sink == null ? DEFAULT_DIAGNOSTIC : sink);
    }

    /** True once a control was actually inserted since installation, not merely registered. */
    public static boolean controlAttached() {
        return CONTROL_ATTACHED.get();
    }

    static void markControlAttached() {
        CONTROL_ATTACHED.set(true);
    }

    static void diagnostic(final String stage) {
        try {
            DIAGNOSTIC.get().accept("MESH_MIRROR_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            // Diagnostics must never reach the host call site.
        }
    }

    /**
     * Binds the bridge in the enabled state.
     *
     * <p>Equivalent to {@link #install(RuntimeMeshMirrorAxisService, RuntimeMeshEditUiService,
     * boolean)} with {@code enabled} true.
     *
     * @param axis the axis service the transformed host methods will consult
     * @param ui   the mesh-edit UI service used to attach native controls
     * @throws IllegalStateException if a binding is already installed
     */
    public static void install(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui
    ) {
        install(axis, ui, true);
    }

    /**
     * Binds the services the transformed host methods call back into.
     *
     * <p>At most one binding exists at a time; installation is a compare-and-set, so a second
     * install without an intervening {@link #uninstall()} is rejected rather than silently replacing
     * the first. While {@code enabled} is false the binding exists but the point, hit and pivot
     * hooks behave as if absent; {@link #attachControl} and {@link #drawAxis} still act.
     *
     * @param axis    the axis service the transformed host methods will consult
     * @param ui      the mesh-edit UI service used to attach native controls
     * @param enabled whether the geometry hooks take effect
     * @throws IllegalStateException if a binding is already installed
     */
    public static void install(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final boolean enabled
    ) {
        if (!INSTALLED.compareAndSet(null, new Binding(axis, ui, enabled))) {
            throw new IllegalStateException("mesh mirror bridge is already installed");
        }
        replayPendingAttach();
    }

    /**
     * Drops the binding and every remembered host object, returning the bridge to its fail-closed
     * state where all hooks pass their host value through untouched.
     *
     * <p>Idempotent, and safe to call when nothing was installed. It does not undo any bytecode
     * transformation; it only makes the transformed calls inert.
     */
    public static void uninstall() {
        INSTALLED.set(null);
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
        PENDING.set(null);
        CONTROL_ATTACHED.set(false);
        DIAGNOSTIC.set(DEFAULT_DIAGNOSTIC);
    }

    /**
     * Attaches a widget the host built before binding. Idempotent and safe to call again:
     * the contribution may still be missing at bind time, in which case the recording is kept
     * and the installer's contribution observer calls back here once it arrives.
     */
    public static void replayPendingAttach() {
        final Binding binding = INSTALLED.get();
        if (binding == null) return;
        final PendingAttach pending = PENDING.get();
        if (pending == null) return;
        if (binding.ui.contribution() == null) {
            diagnostic("DEFERRED_ATTACH_WAITING reason=NO_CONTRIBUTION");
            return;
        }
        if (!PENDING.compareAndSet(pending, null)) return;
        // The contribution observer calls this from the plugin thread, so cleanup can revoke the
        // bridge while we hold a captured binding. Attaching then would put a control into the
        // host UI after the hook was closed; re-check before touching anything native.
        if (INSTALLED.get() != binding) {
            diagnostic("DEFERRED_ATTACH_ABANDONED reason=BRIDGE_REVOKED");
            return;
        }
        diagnostic("DEFERRED_ATTACH_REPLAY");
        attachNow(binding, pending.widget(), pending.panel());
    }

    /**
     * Host hook: replaces a mirrored point with one reflected across the rotated mirror axis.
     *
     * <p>Fail-closed. The host's own {@code original} value is returned unchanged whenever nothing is
     * installed, the binding is disabled, the mirror angle is zero, {@code source} is null, the axis
     * line cannot be resolved, or any reflective access fails. The result is a new instance of
     * {@code source}'s own class, built through its {@code (float, float)} constructor.
     *
     * @param original    the value the host computed, returned unchanged on any refusal
     * @param mirrorState the host's mirror state object; accepted for call-site shape and not read
     * @param source      the point being mirrored; read through its {@code getX}/{@code getY}
     * @return the reflected point, or {@code original}
     */
    public static Object adjustPoint(
        final Object original,
        final Object mirrorState,
        final Object source
    ) {
        return adjust(original, mirrorState, source, false);
    }

    /**
     * Host hook: replaces an on-axis point with its projection onto the rotated mirror axis.
     *
     * <p>Identical contract to {@link #adjustPoint}, except that the point is projected onto the axis
     * rather than reflected across it; equally fail-closed.
     *
     * @param original    the value the host computed, returned unchanged on any refusal
     * @param mirrorState the host's mirror state object; accepted for call-site shape and not read
     * @param source      the point being adjusted; read through its {@code getX}/{@code getY}
     * @return the projected point, or {@code original}
     */
    public static Object adjustAxisPoint(
        final Object original,
        final Object mirrorState,
        final Object source
    ) {
        return adjust(original, mirrorState, source, true);
    }

    /**
     * Host hook: re-answers a mirror-axis pick test against the rotated axis.
     *
     * <p>Fail-closed: returns the host's own {@code original} verdict when nothing is installed, the
     * binding is disabled, the angle is zero, {@code source} is null, the line cannot be resolved, or
     * reflection fails.
     *
     * @param original    the host's own hit verdict, returned unchanged on any refusal
     * @param mirrorState the host's mirror state object; accepted for call-site shape and not read
     * @param source      the picked point, read through its {@code getX}/{@code getY}
     * @param threshold   the host's pick radius, applied as a strict upper bound
     * @return whether the point is within {@code threshold} of the rotated axis
     */
    public static boolean adjustHit(
        final boolean original,
        final Object mirrorState,
        final Object source,
        final float threshold
    ) {
        final Binding binding = INSTALLED.get();
        if (binding == null || !binding.enabled || binding.axis.currentAngleDegrees() == 0.0f || source == null) return original;
        try {
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
            return line == null ? original : MeshMirrorGeometry.hit(
                line, coordinate(source, "getX"), coordinate(source, "getY"), threshold
            );
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return original;
        }
    }

    /**
     * Host hook: injects the angle control into the freshly built mesh-edit mirror widget.
     *
     * <p>Always returns the widget unchanged; the return value exists so the hook can sit inside the
     * host's expression, not to substitute a different widget. Switching to a different panel resets
     * the UI session and clears the remembered pivot first. Runs on the Cubism host UI thread, since
     * the host builds the widget there. Failures while attaching are swallowed, leaving the native UI
     * exactly as the host built it.
     *
     * @param widget the widget the host just created; returned as-is, including when null
     * @param panel  the owning mesh-edit tool panel, used to locate the canvas pivot
     * @return the same widget instance that was passed in
     */
    public static Object attachControl(final Object widget, final Object panel) {
        final Binding binding = INSTALLED.get();
        diagnostic("WIDGET_CALLBACK_ENTER bound=" + (binding != null));
        if (widget == null || panel == null) {
            diagnostic("ATTACH_SKIPPED reason=NULL_HOST_ARGUMENT");
            return widget;
        }
        if (binding == null) {
            PENDING.set(new PendingAttach(panel, widget));
            diagnostic("DEFERRED_ATTACH_PENDING");
            return widget;
        }
        return attachNow(binding, widget, panel);
    }

    private static Object attachNow(final Binding binding, final Object widget, final Object panel) {
        final Object previousPanel = CURRENT_PANEL.getAndSet(panel);
        if (previousPanel != null && previousPanel != panel) {
            binding.ui.resetSession();
            binding.axis.clearPivot();
        }
        try {
            binding.ui.attachNative(panel, widget, binding.axis);
            observePanelPivot(binding, panel);
        } catch (RuntimeException failure) {
            // Native UI remains unchanged.
            diagnostic("ATTACH_FAILED reason=" + failure.getClass().getName());
        }
        return widget;
    }

    /**
     * Host hook: records the rotation pivot the host is currently mirroring about.
     *
     * <p>No-op when nothing is installed or the binding is disabled.
     *
     * @param pivotX pivot x in host document coordinates
     * @param pivotY pivot y in host document coordinates
     */
    public static void observePivot(final float pivotX, final float pivotY) {
        final Binding binding = INSTALLED.get();
        if (binding != null && binding.enabled) binding.axis.observePivot(pivotX, pivotY);
    }

    /**
     * Host hook: forgets the recorded pivot, so the axis falls back until a pivot is observed again.
     *
     * <p>Applies even to a disabled binding, and is a no-op when nothing is installed.
     */
    public static void clearPivot() {
        final Binding binding = INSTALLED.get();
        if (binding != null) binding.axis.clearPivot();
    }

    /**
     * Host hook: draws the mirror axis rotated, in place of the host's axis-aligned line.
     *
     * <p>Returns {@code false} to tell the host to draw the line itself; that happens whenever nothing
     * is installed, the angle is zero, {@code drawImpl} is null, the native draw chain cannot be
     * resolved reflectively, or the axis line degenerates. The rotation pivot is the canvas centre,
     * falling back to the native segment's midpoint when the canvas cannot be reached. The rotated
     * line is drawn with the same half-length as the segment the host would have drawn.
     *
     * <p>Must run on the host's render thread, since it calls straight into the host renderer.
     *
     * @param drawImpl  the host draw context, source of the renderer and view geometry
     * @param axisValue the axis position the host asked for, in document coordinates
     * @param vertical  whether the host axis is vertical
     * @param lineWidth the stroke width passed to the host renderer
     * @param color     the host colour object, passed through unmodified
     * @return {@code true} when this method drew the axis, {@code false} to let the host draw it
     */
    public static boolean drawAxis(
        final Object drawImpl,
        final float axisValue,
        final boolean vertical,
        final float lineWidth,
        final Object color
    ) {
        final Binding binding = INSTALLED.get();
        if (binding == null || binding.axis.currentAngleDegrees() == 0.0f || drawImpl == null) return false;
        try {
            final NativeSegment nativeSegment = nativeSegment(drawImpl, axisValue, vertical);
            if (nativeSegment == null) return false;
            MeshMirrorGeometry.Point pivot = canvasCenter(CURRENT_PANEL.get());
            if (pivot == null) {
                // Fallback: segment midpoint when the canvas center is unavailable.
                pivot = new MeshMirrorGeometry.Point(
                    (nativeSegment.start.x() + nativeSegment.end.x()) * 0.5f,
                    (nativeSegment.start.y() + nativeSegment.end.y()) * 0.5f
                );
            }
            binding.axis.observeAxis(axisValue, vertical, pivot.x(), pivot.y());
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
            if (line == null) return false;
            final float radius = (float) Math.hypot(
                nativeSegment.end.x() - nativeSegment.start.x(),
                nativeSegment.end.y() - nativeSegment.start.y()
            ) * 0.5f;
            final MeshMirrorGeometry.Point start = new MeshMirrorGeometry.Point(
                line.anchor().x() - line.direction().x() * radius,
                line.anchor().y() - line.direction().y() * radius
            );
            final MeshMirrorGeometry.Point end = new MeshMirrorGeometry.Point(
                line.anchor().x() + line.direction().x() * radius,
                line.anchor().y() + line.direction().y() * radius
            );
            if (!invokeVoid(nativeSegment.drawer, "a", vector(drawImpl, start), vector(drawImpl, end), color, lineWidth, 0.0f)) {
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static Object adjust(
        final Object original,
        final Object mirrorState,
        final Object source,
        final boolean projection
    ) {
        final Binding binding = INSTALLED.get();
        if (binding == null || !binding.enabled
            || binding.axis.currentAngleDegrees() == 0.0f || source == null) return original;
        try {
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
            if (line == null) return original;
            final MeshMirrorGeometry.Point point = projection
                ? MeshMirrorGeometry.project(line, coordinate(source, "getX"), coordinate(source, "getY"))
                : MeshMirrorGeometry.reflect(line, coordinate(source, "getX"), coordinate(source, "getY"));
            final Constructor<?> constructor = source.getClass().getConstructor(float.class, float.class);
            return constructor.newInstance(point.x(), point.y());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return original;
        }
    }


    /**
     * Forgets the remembered panel and canvas identity and clears the pivot, without dropping the
     * binding.
     *
     * <p>Use when the host tears down or swaps the mesh-edit context, so stale host objects are not
     * retained and the next attach re-derives the pivot. Safe when no binding is installed.
     */
    public static void clearHostContext() {
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
        final Binding binding = INSTALLED.get();
        if (binding != null) binding.axis.clearPivot();
    }


    private static NativeSegment nativeSegment(
        final Object drawImpl,
        final float axisValue,
        final boolean vertical
    ) throws ReflectiveOperationException {
        final Object viewContext = invoke(drawImpl, "a");
        final Object completePack = invoke(drawImpl, "b");
        final Object renderSystem = invoke(viewContext, "getRenderSystem");
        final Object drawer = invoke(renderSystem, "b");
        final Object sceneGraph = invoke(viewContext, "getSceneGraph");
        final Object objectsOnCanvas = invoke(sceneGraph, "getObjectsOnCanvas");
        final Object sortingLayer = staticField(drawImpl.getClass().getClassLoader(), "com.live2d.graphics3d.component.a.c$b", "a");
        if (!invokeVoid(drawer, "a", objectsOnCanvas)
            || !invokeVoid(drawer, "a", sortingLayer)
            || !invokeVoid(drawer, "a", 51)) return null;

        final Object viewport = invoke(invoke(viewContext, "getViewArea"), "getViewAreaViewport");
        final Object panelRect = invoke(invoke(completePack, "getMainViewPanel"), "getRect");
        final Object camera = invoke(viewContext, "getCameraManager");
        final int viewX = number(invoke(viewport, "getX")).intValue();
        final int viewY = number(invoke(viewport, "getY")).intValue();
        final int viewW = number(invoke(viewport, "getW")).intValue();
        final int viewH = number(invoke(viewport, "getH")).intValue();
        final int panelH = number(invoke(panelRect, "getH")).intValue();
        final Object topLeft = point(drawImpl.getClass().getClassLoader(), viewX, -viewY);
        final Object bottomRight = point(drawImpl.getClass().getClassLoader(), viewX + viewW, -viewY + viewH + panelH);
        final Object docTopLeft = invoke(camera, "componentToDocument", topLeft);
        final Object docBottomRight = invoke(camera, "componentToDocument", bottomRight);
        final float left = coordinate(docTopLeft, "getX");
        final float top = coordinate(docTopLeft, "getY");
        final float right = coordinate(docBottomRight, "getX");
        final float bottom = coordinate(docBottomRight, "getY");
        return vertical
            ? new NativeSegment(drawer, new MeshMirrorGeometry.Point(axisValue, bottom), new MeshMirrorGeometry.Point(axisValue, top))
            : new NativeSegment(drawer, new MeshMirrorGeometry.Point(left, axisValue), new MeshMirrorGeometry.Point(right, axisValue));
    }

    private static MeshMirrorGeometry.Point rotate(
        final MeshMirrorGeometry.Point point,
        final MeshMirrorGeometry.Point pivot,
        final float angleDegrees
    ) {
        final double radians = Math.toRadians(angleDegrees);
        final float x = point.x() - pivot.x();
        final float y = point.y() - pivot.y();
        return new MeshMirrorGeometry.Point(
            pivot.x() + (float) (x * Math.cos(radians) - y * Math.sin(radians)),
            pivot.y() + (float) (x * Math.sin(radians) + y * Math.cos(radians))
        );
    }

    private static MeshMirrorGeometry.Line line(
        final MeshMirrorGeometry.Point start,
        final MeshMirrorGeometry.Point end
    ) {
        final float x = end.x() - start.x();
        final float y = end.y() - start.y();
        final float length = (float) Math.hypot(x, y);
        return length < 0.0001f ? null
            : new MeshMirrorGeometry.Line(start, new MeshMirrorGeometry.Point(x / length, y / length));
    }

    private static Object vector(final Object host, final MeshMirrorGeometry.Point point)
        throws ReflectiveOperationException {
        final Class<?> type = Class.forName("com.live2d.graphics3d.type.GVector2", false, host.getClass().getClassLoader());
        return type.getConstructor(float.class, float.class).newInstance(point.x(), point.y());
    }

    private static Object point(final ClassLoader loader, final int x, final int y)
        throws ReflectiveOperationException {
        final Class<?> type = Class.forName("com.live2d.type.CPoint", false, loader);
        return type.getConstructor(int.class, int.class).newInstance(x, y);
    }

    private static Object staticField(final ClassLoader loader, final String className, final String fieldName)
        throws ReflectiveOperationException {
        final Field field = Class.forName(className, false, loader).getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(null);
    }

    private static boolean observePanelPivot(final Binding binding, final Object panel) {
        try {
            final CanvasPivot canvasPivot = canvasPivot(panel);
            final Object context = new ContextIdentity(
                canvasPivot.viewContext(),
                canvasPivot.editMode(),
                canvasPivot.model(),
                canvasPivot.source(),
                canvasPivot.canvas()
            );
            final Object previous = CURRENT_CONTEXT.getAndSet(context);
            if (previous != null && !previous.equals(context)) binding.axis.clearPivot();
            binding.axis.observePivot(canvasPivot.centerX(), canvasPivot.centerY());
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            CURRENT_CONTEXT.set(null);
            binding.axis.clearPivot();
            return false;
        }
    }

    private static CanvasPivot canvasPivot(final Object panel) throws ReflectiveOperationException {
        final Object toolMode = property(panel, "toolMode");
        final Object controller = invoke(toolMode, "getCtrl$cubism");
        final Object completePack = property(controller, "completePack");
        final Object viewContext = property(completePack, "currentViewContext");
        final Object editMode = property(viewContext, "currentEditMode");
        final Object model = property(editMode, "currentModel");
        final Object source = property(model, "source");
        final Object canvas = property(source, "canvas");
        return new CanvasPivot(
            viewContext,
            editMode,
            model,
            source,
            canvas,
            number(invokeEither(canvas, "getPixelWidth", "getWidth")).floatValue() * 0.5f,
            number(invokeEither(canvas, "getPixelHeight", "getHeight")).floatValue() * 0.5f
        );
    }

    /** Canvas-center pivot, or null when the panel chain is unavailable. */
    private static MeshMirrorGeometry.Point canvasCenter(final Object panel) {
        try {
            final CanvasPivot canvasPivot = canvasPivot(panel);
            return new MeshMirrorGeometry.Point(canvasPivot.centerX(), canvasPivot.centerY());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object field(final Object target, final String name) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchFieldException(name);
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                final Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** getXxx() → getXxx$cubism() → field; a null target throws (fail-closed). */
    private static Object property(final Object target, final String name) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchFieldException(name);
        final String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Object value = tryInvoke(target, getter);
        if (value == null) value = tryInvoke(target, getter + "$cubism");
        if (value == null) value = field(target, name);
        return value;
    }

    private static Object tryInvoke(final Object target, final String name) {
        try {
            return invoke(target, name);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invokeEither(final Object target, final String first, final String second)
        throws ReflectiveOperationException {
        try {
            return invoke(target, first);
        } catch (NoSuchMethodException ignored) {
            return invoke(target, second);
        }
    }

    private static Number number(final Object value) {
        if (value instanceof Number number) return number;
        throw new IllegalArgumentException("expected number");
    }

    private static Object invoke(final Object target, final String name, final Object... arguments)
        throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException(name);
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
                try {
                    return method.invoke(target, arguments);
                } catch (IllegalArgumentException ignored) {
                    // Try the next overload.
                }
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }

    private static boolean invokeVoid(final Object target, final String name, final Object... arguments)
        throws ReflectiveOperationException {
        invoke(target, name, arguments);
        return true;
    }

    private static float coordinate(final Object point, final String getter)
        throws ReflectiveOperationException {
        return number(invoke(point, getter)).floatValue();
    }

    private record NativeSegment(
        Object drawer,
        MeshMirrorGeometry.Point start,
        MeshMirrorGeometry.Point end
    ) { }


    private record CanvasPivot(
        Object viewContext,
        Object editMode,
        Object model,
        Object source,
        Object canvas,
        float centerX,
        float centerY
    ) { }

    private record ContextIdentity(
        Object viewContext,
        Object editMode,
        Object model,
        Object source,
        Object canvas
    ) {
        @Override
        public boolean equals(final Object other) {
            return other instanceof ContextIdentity value
                && viewContext == value.viewContext
                && editMode == value.editMode
                && model == value.model
                && source == value.source
                && canvas == value.canvas;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(viewContext);
        }
    }

    private record Binding(
        RuntimeMeshMirrorAxisService axis,
        RuntimeMeshEditUiService ui,
        boolean enabled
    ) { }

    /** Exact references delivered by the accepted public callback; never resolved by search. */
    private record PendingAttach(Object panel, Object widget) { }
}
