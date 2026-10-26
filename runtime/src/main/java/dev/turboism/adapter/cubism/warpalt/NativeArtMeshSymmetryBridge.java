package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Native modeling-view axis controls and ArtMesh writes inside the host gesture's undo group. */
public final class NativeArtMeshSymmetryBridge {
    private static final String REF = "com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRef";
    private static final String EX = REF + "Ex";
    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);
    private static final Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile int mode;
    private static final RuntimeArtMeshMirrorService SERVICE = new RuntimeArtMeshMirrorService();

    /** Returns the participation service for independent ArtMesh mirror editing. */
    public static RuntimeArtMeshMirrorService service() {
        return SERVICE;
    }

    static boolean isEnabled() {
        return mode != 0;
    }

    static void resetAxes() {
        synchronized (STATES) {
            for (State state : STATES.values()) {
                state.axis = null;
                state.drag = 0;
                state.vertexGesture = false;
                state.pairs.clear();
                state.written.clear();
                Object view = state.view == null ? null : state.view.get();
                if (view != null) {
                    try {
                        call(view, "repaintCanvas");
                    } catch (ReflectiveOperationException failure) {
                        report(failure);
                    }
                }
            }
        }
    }

    private static final class State {
        java.lang.ref.WeakReference<Object> view;
        ArtMeshMirrorAxis axis;
        Set<String> selectedMeshes = Set.of();
        double labelAngle;
        String angleLabel;
        int drag;
        boolean vertexGesture;
        ArtMeshMirrorAxis.Point last;
        final Map<float[], Pairing> pairs = new IdentityHashMap<>();
        final Map<float[], Set<Integer>> written = new IdentityHashMap<>();
    }

    private record Pairing(int[] counterparts) {}

    private NativeArtMeshSymmetryBridge() {}

    static void setMode(int next) {
        mode = next;
        synchronized (STATES) {
            for (State state : STATES.values()) {
                state.drag = 0;
                state.vertexGesture = false;
                state.pairs.clear();
                state.written.clear();
            }
        }
        if (next == 0) CURRENT.remove();
    }

    static void reset() {
        setMode(0);
        STATES.clear();
        CURRENT.remove();
        APPLYING.remove();
        REPORTED.clear();
    }

    private static boolean enabled() {
        return mode != 0 && NativeWarpAltMirrorBridge.active() && SERVICE.hasParticipants();
    }

    private static State state(Object view) throws ReflectiveOperationException {
        if (!enabled()
                || view == null
                || !view.getClass().getName().equals("com.live2d.cubism.view.context.CEViewContext_ModelingView"))
            return null;
        Object edit = call(view, "getCurrentEditMode");
        // Exclude form-animation and mesh-topology editors, which own different write policies.
        if (!edit.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingEditMode_Main")) return null;
        Object source = call(call(edit, "getCurrentModel"), "getSource");
        synchronized (STATES) {
            State state = STATES.computeIfAbsent(source, ignored -> new State());
            state.view = new java.lang.ref.WeakReference<>(view);
            return state;
        }
    }

    /** Called before native input dispatch. Only a hit on the axis controls captures a gesture. */
    public static boolean input(Object view, Object event) {
        try {
            State state = state(view);
            CURRENT.remove();
            if (state == null) return false;
            if (!syncSelectedMeshes(view, state)) {
                state.drag = 0;
                return false;
            }
            CURRENT.set(state);
            Object awt = call(event, "f");
            if (!(awt instanceof MouseEvent mouse)) return false;
            int id = mouse.getID();
            if (id == MouseEvent.MOUSE_PRESSED) {
                state.vertexGesture = mouse.getButton() == MouseEvent.BUTTON1;
                state.drag = 0;
                state.pairs.clear();
                state.written.clear();
            }
            if (state.axis == null) initialize(view, state);
            if (state.axis == null) return false;
            Object camera = call(view, "getCameraManager");
            if (!Boolean.TRUE.equals(call(camera, "isSafeEditOnPlaneXY"))) return false;
            ArtMeshMirrorAxis.Point point = read(call(camera, "componentToDocument", call(event, "K")));
            if (id == MouseEvent.MOUSE_PRESSED
                    && mouse.getButton() == MouseEvent.BUTTON1
                    && !mouse.isAltDown()
                    && !mouse.isControlDown()
                    && !mouse.isMetaDown()) {
                ArtMeshMirrorAxis.Point screen = project(camera, view, point);
                ProjectedAxis projection = projectAxis(camera, view, state.axis);
                ArtMeshMirrorAxis.Point center = projection.center();
                ArtMeshMirrorAxis.Point handle = rotationHandle(projection, state.axis);
                if (distance(screen, handle) <= 12) state.drag = 3;
                else if (distance(screen, center) <= 11) state.drag = 2;
                else {
                    float dx = projection.direction().x();
                    float dy = projection.direction().y();
                    double length = Math.hypot(dx, dy);
                    double perpendicular =
                            Math.abs((screen.x() - center.x()) * dy - (screen.y() - center.y()) * dx) / length;
                    if (perpendicular <= 5) state.drag = 1;
                }
                if (state.drag != 0) state.vertexGesture = false;
                state.last = point;
                if (state.drag != 0 && mouse.getClickCount() == 2) {
                    state.axis = null;
                    initialize(view, state);
                    state.drag = 0;
                    call(view, "repaintCanvas");
                    return true;
                }
            } else if (state.drag != 0 && id == MouseEvent.MOUSE_DRAGGED) {
                if (state.drag == 1 || state.drag == 2) state.axis.translate(point.minus(state.last));
                else state.axis.rotate(point);
                state.last = point;
                state.pairs.clear();
            } else if (id == MouseEvent.MOUSE_RELEASED) {
                state.vertexGesture = false;
                boolean captured = state.drag != 0;
                state.drag = 0;
                state.pairs.clear();
                state.written.clear();
                if (captured) {
                    call(view, "repaintCanvas");
                    return true;
                }
            }
            if (state.drag != 0) {
                call(view, "repaintCanvas");
                return true;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
            CURRENT.remove();
        }
        return false;
    }

    /** The native action dispatcher starts a new tick; pair identities survive until release. */
    static void beginTick(Object pack) {
        try {
            State state = state(call(pack, "ab"));
            CURRENT.remove();
            if (state != null) {
                state.written.clear();
                CURRENT.set(state);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
            CURRENT.remove();
        }
    }

    private static boolean syncSelectedMeshes(Object view, State state) throws ReflectiveOperationException {
        Object selector = call(call(view, "getCurrentEditMode"), "getSelector");
        Set<String> selected = new java.util.HashSet<>();
        for (Object selection : (Iterable<?>) call(selector, "getSelected")) {
            Object source = call(selection, "getSource");
            if (source != null
                    && source.getClass()
                            .getName()
                            .equals("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource"))
                selected.add(String.valueOf(call(selection, "getGuid")));
        }
        if (!selected.equals(state.selectedMeshes)) {
            state.selectedMeshes = Set.copyOf(selected);
            state.axis = null;
            state.drag = 0;
            state.vertexGesture = false;
            state.pairs.clear();
            state.written.clear();
        }
        return !selected.isEmpty();
    }

    private static void initialize(Object view, State state) throws ReflectiveOperationException {
        Object edit = call(view, "getCurrentEditMode");
        Object model = call(edit, "getCurrentModel");
        Object selections = call(call(edit, "getSelector"), "getSelected");
        if (!(selections instanceof Iterable<?> list)) return;
        java.util.List<Object> forms = new java.util.ArrayList<>();
        for (Object selection : list) {
            Object source = call(selection, "getSource");
            if (!source.getClass().getName().equals("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource"))
                continue;
            Object mesh = call(model, "getArtMesh", call(selection, "getGuid"));
            forms.add(call(mesh, "getCalculatedForm"));
        }
        state.axis = axisForCalculatedForms(forms, mode);
        if (state.axis != null) {
            Object camera = call(view, "getCameraManager");
            state.axis.setRotationRadius(
                    64 / projectAxis(camera, view, state.axis).scale());
        }
    }

    static ArtMeshMirrorAxis axisForCalculatedForms(Iterable<?> forms, int axisMode)
            throws ReflectiveOperationException {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (Object form : forms) {
            // Calculated forms already contain canvas coordinates. Applying the model's
            // local-to-canvas transform again would push the pivot far outside the viewport.
            float[] positions = (float[]) call(form, "getPositions");
            for (int i = 0; i + 1 < positions.length; i += 2) {
                minX = Math.min(minX, positions[i]);
                maxX = Math.max(maxX, positions[i]);
                minY = Math.min(minY, positions[i + 1]);
                maxY = Math.max(maxY, positions[i + 1]);
            }
        }
        return Float.isFinite(minX)
                ? new ArtMeshMirrorAxis(
                        new ArtMeshMirrorAxis.Point((minX + maxX) / 2, (minY + maxY) / 2), axisMode == 1)
                : null;
    }

    /** Model-anchored controls painted in the native component layer, with bounded draw work. */
    public static void draw(Object view) {
        try {
            State state = state(view);
            if (state == null || !syncSelectedMeshes(view, state)) return;
            if (state.axis == null) initialize(view, state);
            if (state.axis == null) return;
            Object camera = call(view, "getCameraManager");
            if (!Boolean.TRUE.equals(call(camera, "isSafeEditOnPlaneXY"))) return;
            DrawingResources resources = DRAWING.get(view.getClass());
            Object drawer = call(call(view, "getRenderSystem"), "b");
            Object graph = call(view, "getSceneGraph");
            call(drawer, "a", call(graph, "getObjectsOnComponent"));
            call(drawer, "a", resources.layer);
            ProjectedAxis projection = projectAxis(camera, view, state.axis);
            var center = projection.center();
            var direction = projection.direction();
            Object rect = call(camera, "getComponentRect");
            float left = ((Number) call(rect, "getX")).floatValue();
            float top = ((Number) call(rect, "getY")).floatValue();
            float width = ((Number) call(rect, "getWidth")).floatValue();
            float height = ((Number) call(rect, "getHeight")).floatValue();
            float radius = (float) (Math.hypot(center.x() - left, center.y() - top) + Math.hypot(width, height));
            var start = new ArtMeshMirrorAxis.Point(
                    center.x() - direction.x() * radius, center.y() - direction.y() * radius);
            var end = new ArtMeshMirrorAxis.Point(
                    center.x() + direction.x() * radius, center.y() + direction.y() * radius);
            call(drawer, "a", 90);
            line(drawer, view, start, end, resources.dark, 4);
            call(drawer, "a", 91);
            line(drawer, view, start, end, resources.light, 1.5f);
            marker(drawer, view, center, resources, true);
            var handle = rotationHandle(projection, state.axis);
            marker(drawer, view, handle, resources, false);
            if (state.angleLabel == null || state.labelAngle != state.axis.angle()) {
                state.labelAngle = state.axis.angle();
                state.angleLabel = String.format(java.util.Locale.ROOT, "%.1f°", Math.toDegrees(state.labelAngle));
            }
            // Outline the glyphs, leaving the canvas visible behind the label.
            call(drawer, "a", 97);
            for (int side = 0; side < 4; side++) {
                float dx = side == 0 ? -1 : side == 1 ? 1 : 0;
                float dy = side == 2 ? -1 : side == 3 ? 1 : 0;
                angleText(
                        drawer, state.angleLabel, resources, handle.x() + 15 + dx, handle.y() + 6 + dy, resources.dark);
            }
            call(drawer, "a", 98);
            angleText(drawer, state.angleLabel, resources, handle.x() + 15, handle.y() + 6, resources.light);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
        }
    }

    /** Returns true only when this tick already wrote this selected counterpart. */
    public static boolean move(Object ref, Object target, float weight) {
        if (!enabled() || APPLYING.get() || !Float.isFinite(weight) || weight <= 0) return false;
        State state = CURRENT.get();
        if (state == null || !state.vertexGesture || state.axis == null || !dragCaller()) return false;
        try {
            float[] positions = (float[]) call(ref, "h");
            int index = ((Number) call(ref, "a")).intValue();
            Set<Integer> written = state.written.computeIfAbsent(positions, ignored -> new java.util.HashSet<>());
            if (written.contains(index)) return true;
            Pairing pairs = pairing(state, ref);
            int counterpart = pairs.counterparts[index];
            if (counterpart < 0) return false;
            Object other = counterpart(ref, counterpart);
            ArtMeshMirrorAxis.Point current = read(call(ref, "e"));
            // Mirror the actual weighted displacement in canvas coordinates. Parent transforms
            // can be nonlinear and index-dependent, so blending in canvas space is not equivalent.
            Field transformField = MethodHandleCache.declaredFieldUp(ref.getClass(), "transformBetweenLocalAndCanvas");
            ReflectAccess.open(transformField);
            Object transform = transformField.get(ref);
            Object localTarget =
                    transform == null ? target : call(transform, "transformCanvasToLocal", target, null, index);
            ArtMeshMirrorAxis.Point local = read(call(ref, "getPos"));
            ArtMeshMirrorAxis.Point requested = read(localTarget);
            ArtMeshMirrorAxis.Point blended = new ArtMeshMirrorAxis.Point(
                    local.x() * (1 - weight) + requested.x() * weight,
                    local.y() * (1 - weight) + requested.y() * weight);
            ArtMeshMirrorAxis.Point actual = transform == null
                    ? blended
                    : read(call(transform, "transformLocalToCanvas", vector(ref, blended), null, index));
            ArtMeshMirrorAxis.Point delta = state.axis.reflectVector(actual.minus(current));
            ArtMeshMirrorAxis.Point next = read(call(other, "e")).plus(delta);
            APPLYING.set(true);
            try {
                call(other, "a", vector(ref, next), 1f);
            } finally {
                APPLYING.set(false);
            }
            written.add(index);
            written.add(counterpart);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
        }
        return false;
    }
    /** Shares the same pairing as movement; native selector operations retain their own undo semantics. */
    static void weight(Object selector, Object ref, float weight, boolean reorder, boolean setOnly) {
        if (!enabled() || APPLYING.get() || selector == null || ref == null) return;
        State state = CURRENT.get();
        if (state == null || state.axis == null) return;
        try {
            ClassLoader loader = ref.getClass().getClassLoader();
            if (!Class.forName(REF, false, loader).isInstance(ref)) return;
            Object model = call(call(state.view.get(), "getCurrentEditMode"), "getCurrentModel");
            Object source = call(ref, "d");
            Object mesh = call(model, "getArtMesh", call(source, "getId"));
            if (mesh == null || call(mesh, "getSource") != source) return;
            Object form = call(ref, "g");
            if (form == null) form = call(mesh, "getCalculatedForm");
            Object ex = call(form, "getCompatiblePointRefEx", ref, model);
            Pairing pairs = pairing(state, ex);
            int index = ((Number) call(ex, "a")).intValue();
            int counterpart = pairs.counterparts[index];
            if (counterpart < 0) return;
            Object other = counterpart(ex, counterpart);
            Object compatible = call(selector, "getCompatible", other);
            if (compatible != null) other = compatible;
            APPLYING.set(true);
            try {
                if (setOnly) call(selector, "setWeight", other, weight);
                else call(selector, "add", other, weight, reorder);
            } finally {
                APPLYING.set(false);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
        }
    }

    private static boolean dragCaller() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String name = frame.getClassName();
            if (name.startsWith("dev.turboism.") || name.equals("java.lang.Thread") || name.equals(EX)) continue;
            // Point dragging and brush movement use different, version-obfuscated action
            // owners. The active left-button gesture and main-modeling view gate writes;
            // scripts, undo, and non-action writes cannot enter this path.
            return name.startsWith("com.live2d.cubism.view.context.action.");
        }
        return false;
    }

    private static Pairing pairing(State state, Object ref) throws ReflectiveOperationException {
        float[] positions = (float[]) call(ref, "h");
        Pairing cached = state.pairs.get(positions);
        if (cached != null && cached.counterparts.length * 2 == positions.length) return cached;
        int count = positions.length / 2;
        if (count == 0 || count > 10000 || count * 2 != positions.length)
            throw new IllegalArgumentException("mesh size");
        ArtMeshMirrorAxis.Point[] points = new ArtMeshMirrorAxis.Point[count];
        for (int i = 0; i < count; i++) points[i] = read(call(counterpart(ref, i), "e"));
        Pairing value = new Pairing(pairForView(state.view.get(), state.axis, points));
        state.pairs.put(positions, value);
        return value;
    }

    static int[] pairForView(Object view, ArtMeshMirrorAxis axis, ArtMeshMirrorAxis.Point[] points)
            throws ReflectiveOperationException {
        // Match the native topology editor's move tolerance: 20 component pixels.
        // A mesh-relative tolerance rejects slightly misaligned arbitrary-angle pairs.
        Object camera = call(view, "getCameraManager");
        float tolerance = 20f / projectAxis(camera, view, axis).scale();
        return axis.pair(points, tolerance);
    }

    private static Object counterpart(Object ref, int index) throws ReflectiveOperationException {
        ClassLoader loader = ref.getClass().getClassLoader();
        Class<?> base = Class.forName(REF, false, loader);
        Object source = call(ref, "d"), form = call(ref, "g"), coord = call(ref, "c");
        Object result = base.getConstructor(
                        source.getClass(),
                        int.class,
                        float[].class,
                        int.class,
                        coord.getClass(),
                        Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", false, loader))
                .newInstance(source, index, call(ref, "h"), call(ref, "i"), coord, form);
        Field transform = MethodHandleCache.declaredFieldUp(ref.getClass(), "transformBetweenLocalAndCanvas");
        ReflectAccess.open(transform);
        return Class.forName(EX, false, loader)
                .getConstructor(
                        base,
                        Class.forName("com.live2d.doc.selection.ITransformBetweenLocalAndCanvas", false, loader),
                        Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", false, loader))
                .newInstance(result, transform.get(ref), form);
    }

    private record ProjectedAxis(ArtMeshMirrorAxis.Point center, ArtMeshMirrorAxis.Point direction, float scale) {}

    private static ProjectedAxis projectAxis(Object camera, Object view, ArtMeshMirrorAxis axis)
            throws ReflectiveOperationException {
        var pivot = axis.pivot();
        var center = project(camera, view, pivot);
        // A one-unit probe can round to the same screen pixel when zoomed out, or
        // to the same model float at large coordinates. Use a well-separated probe.
        float step = Math.max(1024, Math.max(Math.ulp(pivot.x()), Math.ulp(pivot.y())) * 64);
        var direction = axis.direction();
        var end = project(
                camera,
                view,
                new ArtMeshMirrorAxis.Point(pivot.x() + direction.x() * step, pivot.y() + direction.y() * step));
        var delta = end.minus(center);
        float length = (float) Math.hypot(delta.x(), delta.y());
        if (!(length > 0)) throw new IllegalStateException("degenerate camera projection");
        return new ProjectedAxis(
                center, new ArtMeshMirrorAxis.Point(delta.x() / length, delta.y() / length), length / step);
    }

    private static ArtMeshMirrorAxis.Point rotationHandle(Object camera, Object view, ArtMeshMirrorAxis axis)
            throws ReflectiveOperationException {
        return rotationHandle(projectAxis(camera, view, axis), axis);
    }

    private static ArtMeshMirrorAxis.Point rotationHandle(ProjectedAxis projection, ArtMeshMirrorAxis axis) {
        float arm = Math.max(32, axis.rotationRadius() * projection.scale());
        return new ArtMeshMirrorAxis.Point(
                projection.center().x() + projection.direction().x() * arm,
                projection.center().y() + projection.direction().y() * arm);
    }

    private static final ClassValue<DrawingResources> DRAWING = new ClassValue<>() {
        @Override
        protected DrawingResources computeValue(Class<?> type) {
            try {
                return new DrawingResources(type.getClassLoader());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class DrawingResources {
        final Object dark, light, font, layer;

        DrawingResources(ClassLoader loader) throws ReflectiveOperationException {
            var color = Class.forName("com.live2d.type.CColor", false, loader)
                    .getConstructor(int.class, int.class, int.class);
            dark = color.newInstance(24, 24, 28);
            light = color.newInstance(255, 255, 255);
            font = Class.forName("com.live2d.type.CFont", false, loader)
                    .getConstructor(int.class)
                    .newInstance(18);
            layer = Class.forName("com.live2d.graphics3d.component.a.c$b", false, loader)
                    .getField("a")
                    .get(null);
        }
    }

    private static void angleText(
            Object drawer, String text, DrawingResources resources, float x, float y, Object color)
            throws ReflectiveOperationException {
        call(drawer, "a", text, resources.font, x, y, 0f, 20f, color);
    }

    private static void marker(
            Object drawer, Object view, ArtMeshMirrorAxis.Point center, DrawingResources resources, boolean diamond)
            throws ReflectiveOperationException {
        float r = diamond ? 9 : 8, x = center.x(), y = center.y();
        ArtMeshMirrorAxis.Point[] corners = diamond
                ? new ArtMeshMirrorAxis.Point[] {
                    new ArtMeshMirrorAxis.Point(x, y - r), new ArtMeshMirrorAxis.Point(x + r, y),
                    new ArtMeshMirrorAxis.Point(x, y + r), new ArtMeshMirrorAxis.Point(x - r, y)
                }
                : new ArtMeshMirrorAxis.Point[] {
                    new ArtMeshMirrorAxis.Point(x - r, y - r), new ArtMeshMirrorAxis.Point(x + r, y - r),
                    new ArtMeshMirrorAxis.Point(x + r, y + r), new ArtMeshMirrorAxis.Point(x - r, y + r)
                };
        for (int layer = 0; layer < 2; layer++) {
            call(drawer, "a", 92 + layer);
            for (int edge = 0; edge < 4; edge++) {
                line(
                        drawer,
                        view,
                        corners[edge],
                        corners[(edge + 1) % 4],
                        layer == 0 ? resources.dark : resources.light,
                        layer == 0 ? 4 : 1.5f);
            }
        }
    }

    private static ArtMeshMirrorAxis.Point project(Object camera, Object host, ArtMeshMirrorAxis.Point point)
            throws ReflectiveOperationException {
        return read(call(camera, "documentToComponent", vector(host, point)));
    }

    private static void line(
            Object drawer, Object host, ArtMeshMirrorAxis.Point a, ArtMeshMirrorAxis.Point b, Object color, float width)
            throws ReflectiveOperationException {
        call(drawer, "a", vector(host, a), vector(host, b), color, width, 0f);
    }

    private static double distance(ArtMeshMirrorAxis.Point a, ArtMeshMirrorAxis.Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static final ClassValue<java.lang.reflect.Constructor<?>> VECTORS = new ClassValue<>() {
        @Override
        protected java.lang.reflect.Constructor<?> computeValue(Class<?> type) {
            try {
                return Class.forName("com.live2d.graphics3d.type.GVector2", false, type.getClassLoader())
                        .getConstructor(float.class, float.class);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static Object vector(Object host, ArtMeshMirrorAxis.Point point) throws ReflectiveOperationException {
        return VECTORS.get(host.getClass()).newInstance(point.x(), point.y());
    }

    private static ArtMeshMirrorAxis.Point read(Object vector) throws ReflectiveOperationException {
        return new ArtMeshMirrorAxis.Point(
                ((Number) call(vector, "getX")).floatValue(), ((Number) call(vector, "getY")).floatValue());
    }

    private static void report(Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        String reason = failure.getClass().getSimpleName() + ":" + failure.getMessage();
        if (REPORTED.add(reason))
            dev.turboism.runtime.log.RuntimeDiagnostics.info(
                    "warp-alt-mirror", "ARTMESH_SYMMETRY_SKIPPED reason=" + reason);
    }

    private record Invocation(String name, java.util.List<Class<?>> arguments) {}

    private static final ClassValue<java.util.concurrent.ConcurrentMap<Invocation, Method>> METHODS =
            new ClassValue<>() {
                @Override
                protected java.util.concurrent.ConcurrentMap<Invocation, Method> computeValue(Class<?> type) {
                    return new java.util.concurrent.ConcurrentHashMap<>();
                }
            };

    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        if (target == null) throw new NoSuchMethodException(name);
        var types = new java.util.ArrayList<Class<?>>(args.length);
        for (Object argument : args) types.add(argument == null ? Void.class : argument.getClass());
        var key = new Invocation(name, java.util.List.copyOf(types));
        var cache = METHODS.get(target.getClass());
        Method method = cache.get(key);
        if (method == null) {
            int best = Integer.MAX_VALUE;
            for (Method candidate : MethodHandleCache.overloads(target.getClass(), name, args.length)) {
                Class<?>[] parameters = candidate.getParameterTypes();
                int score = 0;
                for (int i = 0; i < parameters.length; i++) {
                    Class<?> expected = parameters[i];
                    Class<?> actual = types.get(i);
                    if (actual == Void.class && !expected.isPrimitive()) {
                        score += 2;
                        continue;
                    }
                    if (expected.isPrimitive()) expected = boxed(expected);
                    if (expected == actual) continue;
                    if (!expected.isAssignableFrom(actual)) {
                        score = Integer.MAX_VALUE;
                        break;
                    }
                    score += 1;
                }
                if (score < best) {
                    best = score;
                    method = candidate;
                }
                if (score == 0) break;
            }
            if (method == null)
                throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
            ReflectAccess.open(method);
            cache.putIfAbsent(key, method);
        }
        return method.invoke(target, args);
    }

    private static Class<?> boxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == double.class) return Double.class;
        if (type == long.class) return Long.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return type;
    }
}
