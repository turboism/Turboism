package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

/** Fail-closed static entrypoint used only by the exact mesh-mirror transformer. */
public final class NativeMeshMirrorBridge {
    private static final AtomicReference<Binding> INSTALLED = new AtomicReference<>();
    private static final AtomicReference<Object> CURRENT_PANEL = new AtomicReference<>();
    private static final AtomicReference<Object> CURRENT_CONTEXT = new AtomicReference<>();

    private NativeMeshMirrorBridge() { }

    public static void install(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui
    ) {
        install(axis, ui, true);
    }

    public static void install(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final boolean enabled
    ) {
        if (!INSTALLED.compareAndSet(null, new Binding(axis, ui, enabled))) {
            throw new IllegalStateException("mesh mirror bridge is already installed");
        }
    }

    public static void uninstall() {
        INSTALLED.set(null);
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
    }

    public static Object adjustPoint(
        final Object original,
        final Object mirrorState,
        final Object source
    ) {
        return adjust(original, mirrorState, source, false);
    }

    public static Object adjustAxisPoint(
        final Object original,
        final Object mirrorState,
        final Object source
    ) {
        return adjust(original, mirrorState, source, true);
    }

    public static boolean adjustHit(
        final boolean original,
        final Object mirrorState,
        final Object source,
        final float threshold
    ) {
        final Binding binding = INSTALLED.get();
        if (binding == null || !binding.enabled || binding.axis.currentAngleDegrees() == 0.0f || source == null) return original;
        if (!refreshCurrentContext(binding)) return original;
        try {
            final MeshMirrorGeometry.Line line = line(binding, mirrorState);
            return line == null ? original : MeshMirrorGeometry.hit(
                line, coordinate(source, "getX"), coordinate(source, "getY"), threshold
            );
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return original;
        }
    }

    public static Object attachControl(final Object widget, final Object panel) {
        final Binding binding = INSTALLED.get();
        if (binding == null || widget == null || panel == null) return widget;
        final Object previousPanel = CURRENT_PANEL.getAndSet(panel);
        if (previousPanel != null && previousPanel != panel) {
            binding.ui.resetSession();
            binding.axis.clearPivot();
        }
        try {
            binding.ui.attachNative(panel, widget, binding.axis);
            observePanelPivot(binding, panel);
        } catch (RuntimeException ignored) {
            // Native UI remains unchanged.
        }
        return widget;
    }

    public static void observePivot(final float pivotX, final float pivotY) {
        final Binding binding = INSTALLED.get();
        if (binding != null && binding.enabled) binding.axis.observePivot(pivotX, pivotY);
    }

    public static void clearPivot() {
        final Binding binding = INSTALLED.get();
        if (binding != null) binding.axis.clearPivot();
    }

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
            final MeshMirrorGeometry.Point pivot = new MeshMirrorGeometry.Point(
                (nativeSegment.start.x() + nativeSegment.end.x()) * 0.5f,
                (nativeSegment.start.y() + nativeSegment.end.y()) * 0.5f
            );
            binding.axis.observeAxis(axisValue, vertical, pivot.x(), pivot.y());
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine(null);
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
        if (!refreshCurrentContext(binding)) return original;
        try {
            final MeshMirrorGeometry.Line line = line(binding, mirrorState);
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

    private static MeshMirrorGeometry.Line line(
        final Binding binding,
        final Object mirrorState
    ) {
        return binding.axis.resolveLine(mirrorState);
    }

    public static void clearHostContext() {
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
        final Binding binding = INSTALLED.get();
        if (binding != null) binding.axis.clearPivot();
    }

    private static boolean refreshCurrentContext(final Binding binding) {
        final Object panel = CURRENT_PANEL.get();
        if (panel == null) {
            binding.axis.clearPivot();
            return false;
        }
        return observePanelPivot(binding, panel);
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
            final Object toolMode = field(panel, "toolMode");
            final Object controller = invoke(toolMode, "getCtrl$cubism");
            final Object completePack = field(controller, "completePack");
            final Object viewContext = field(completePack, "currentViewContext");
            final Object editMode = field(viewContext, "currentEditMode");
            final Object model = field(editMode, "currentModel");
            final Object source = field(model, "source");
            final Object canvas = field(source, "canvas");
            final Object context = new ContextIdentity(viewContext, editMode, model, source, canvas);
            final Object previous = CURRENT_CONTEXT.getAndSet(context);
            if (previous != null && !previous.equals(context)) binding.axis.clearPivot();
            final float width = number(invokeEither(canvas, "getPixelWidth", "getWidth")).floatValue();
            final float height = number(invokeEither(canvas, "getPixelHeight", "getHeight")).floatValue();
            binding.axis.observePivot(width * 0.5f, height * 0.5f);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            CURRENT_CONTEXT.set(null);
            binding.axis.clearPivot();
            return false;
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
}
