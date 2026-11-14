package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
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
    private static final String MODELING_VIEW_CLASS = "com.live2d.cubism.view.context.CEViewContext_ModelingView";
    private static final String MAIN_EDIT_MODE_CLASS = "com.live2d.cubism.doc.modeling.CModelingEditMode_Main";
    private static final String ARTMESH_SOURCE_CLASS = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshSource";
    private static final String ART_MESH_FORM_CLASS = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String TRANSFORM_CLASS = "com.live2d.doc.selection.ITransformBetweenLocalAndCanvas";
    private static final String TRANSFORM_FIELD = "transformBetweenLocalAndCanvas";
    private static final String DRAG_ACTION_PACKAGE = "com.live2d.cubism.view.context.action.";
    private static final String HOST_ADAPTER_PACKAGE = "dev.turboism.";
    private static final String THREAD_CLASS = "java.lang.Thread";
    private static final double DEGREES_TO_TENTHS = 10.0;
    private static final float AXIS_LINE_DARK_WIDTH = 4.0f;
    private static final float AXIS_LINE_LIGHT_WIDTH = 1.5f;
    private static final int ORDER_AXIS_DARK = 90;
    private static final int ORDER_AXIS_LIGHT = 91;
    private static final int ORDER_MARKER_BASE = 92;
    private static final int ORDER_LABEL_OUTLINE = 97;
    private static final int ORDER_LABEL_FILL = 98;
    private static final float LABEL_FONT_SIZE_PT = 20.0f;
    private static final int LABEL_FONT_CTOR_SIZE = 18;
    private static final float LABEL_OFFSET_X = 15.0f;
    private static final float LABEL_OFFSET_Y = 6.0f;
    private static final int LABEL_IMAGE_WIDTH = 112;
    private static final int LABEL_IMAGE_HEIGHT = 28;
    private static final int LABEL_DRAW_X = 2;
    private static final int LABEL_DRAW_BASELINE_Y = 21;
    private static final int DARK_R = 24;
    private static final int DARK_G = 24;
    private static final int DARK_B = 28;
    private static final int LIGHT_RGB = 255;
    private static final Color LABEL_OUTLINE_COLOR = new Color(DARK_R, DARK_G, DARK_B);
    private static final Color LABEL_FILL_COLOR = new Color(LIGHT_RGB, LIGHT_RGB, LIGHT_RGB);
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
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
                state.resetGesture = false;
                state.pairs.clear();
                state.written.clear();
                hideAngleLabel(state);
                Object view = state.view == null ? null : state.view.get();
                if (view != null) {
                    try {
                        VIEW_UI_HANDLES.get(view.getClass()).repaintCanvas.invokeExact(view);
                    } catch (Throwable failure) {
                        FatalErrors.rethrowIfFatal(failure);
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
        long labelTenths = Long.MIN_VALUE;
        String angleLabel;
        boolean labelDirty;
        AngleLabelWidget angleWidget;
        int drag;
        boolean vertexGesture;
        /**
         * Set by the double-click reset. The matching release must be consumed as well: a reset is a
         * complete gesture, and letting its release reach the native dispatcher starts a mesh edit
         * the operator never asked for.
         */
        boolean resetGesture;

        NativeWarpPointGesture.Gesture movement;
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
                state.resetGesture = false;
                state.pairs.clear();
                state.written.clear();
                if (next == 0) hideAngleLabel(state);
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

    private static State state(Object view) throws Throwable {
        if (!enabled() || view == null || !view.getClass().getName().equals(MODELING_VIEW_CLASS)) return null;
        StateHandles handles = STATE_HANDLES.get(view.getClass());
        Object edit = handles.getCurrentEditMode.invokeExact(view);
        // Exclude form-animation and mesh-topology editors, which own different write policies.
        if (edit == null || !edit.getClass().getName().equals(MAIN_EDIT_MODE_CLASS)) return null;
        Object model = handles.getCurrentModel.invoke(edit);
        Object source = handles.getModelSource(model).invoke(model);
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
            ViewUiHandles handles = VIEW_UI_HANDLES.get(view.getClass());
            EventHandles eventHandles = EVENT_HANDLES.get(event.getClass());
            if (eventHandles.awt == null) return false;
            Object awt = eventHandles.awt.invoke(event);
            if (!(awt instanceof MouseEvent mouse)) return false;
            int id = mouse.getID();
            // Skip selection polling while actively dragging the captured axis.
            if (!(state.drag != 0 && id == MouseEvent.MOUSE_DRAGGED)) {
                if (!syncSelectedMeshes(view, state, handles)) {
                    state.drag = 0;
                    return false;
                }
            }
            CURRENT.set(state);
            if (id == MouseEvent.MOUSE_PRESSED) {
                state.movement = null;
                state.vertexGesture = mouse.getButton() == MouseEvent.BUTTON1;
                state.drag = 0;
                state.resetGesture = false;
                state.pairs.clear();
                state.written.clear();
            }
            if (state.axis == null) initialize(view, state);
            if (state.axis == null) return false;
            boolean needsPoint = id == MouseEvent.MOUSE_PRESSED || (state.drag != 0 && id == MouseEvent.MOUSE_DRAGGED);
            if (!needsPoint) {
                if (id == MouseEvent.MOUSE_RELEASED) {
                    state.movement = null;
                    state.vertexGesture = false;
                    boolean captured = state.drag != 0 || state.resetGesture;
                    state.drag = 0;
                    state.resetGesture = false;
                    state.pairs.clear();
                    state.written.clear();
                    if (captured) {
                        handles.repaintCanvas.invokeExact(view);
                        return true;
                    }
                }
                return false;
            }
            Object camera = handles.getCameraManager.invokeExact(view);
            if (!(boolean) handles.isSafeEditOnPlaneXY.invoke(camera)) return false;
            if (eventHandles.componentPoint == null) return false;
            Object eventPoint = eventHandles.componentPoint.invoke(event);
            ArtMeshMirrorAxis.Point point = read(handles, handles.componentToDocument.invoke(camera, eventPoint));
            if (id == MouseEvent.MOUSE_PRESSED && mouse.getButton() == MouseEvent.BUTTON1)
                state.movement = new NativeWarpPointGesture.Gesture(view, point.x(), point.y());
            if (id == MouseEvent.MOUSE_PRESSED
                    && mouse.getButton() == MouseEvent.BUTTON1
                    && !mouse.isAltDown()
                    && !mouse.isControlDown()
                    && !mouse.isMetaDown()) {
                ArtMeshMirrorAxis.Point screen = project(handles, camera, point);
                ProjectedAxis projection = projectAxis(handles, camera, state.axis);
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
                    state.resetGesture = true;
                    handles.repaintCanvas.invokeExact(view);
                    return true;
                }
            } else if (state.drag != 0 && id == MouseEvent.MOUSE_DRAGGED) {
                if (state.drag == 1 || state.drag == 2) state.axis.translate(point.minus(state.last));
                else state.axis.rotate(point);
                state.last = point;
                state.pairs.clear();
            }
            if (state.drag != 0) {
                handles.repaintCanvas.invokeExact(view);
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
    static void beginTick(Object manager) {
        CURRENT.remove();
        if (!enabled()) return;
        try {
            State state = state(NativeWarpAltMirrorBridge.actionView(manager));
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

    private static boolean syncSelectedMeshes(Object view, State state, ViewUiHandles handles) throws Throwable {
        Object edit = handles.getCurrentEditMode.invokeExact(view);
        Object selector = handles.getSelector.invoke(edit);
        Object selectedList = handles.getSelected.invoke(selector);
        if (!(selectedList instanceof Iterable<?> iterable)) {
            if (!state.selectedMeshes.isEmpty()) clearAxisForSelectionChange(state, Set.of());
            return false;
        }
        Set<String> previous = state.selectedMeshes;
        int matched = 0;
        boolean changed = false;
        for (Object selection : iterable) {
            if (selection == null) continue;
            SelectionHandles sel = SELECTION_HANDLES.get(selection.getClass());
            if (sel.getSource == null || sel.getGuid == null) continue;
            Object source = sel.getSource.invoke(selection);
            if (source != null && source.getClass().getName().equals(ARTMESH_SOURCE_CLASS)) {
                String guid = String.valueOf(sel.getGuid.invoke(selection));
                if (previous.contains(guid)) {
                    matched++;
                } else {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed && matched == previous.size()) {
            return !previous.isEmpty();
        }
        Set<String> selected = new java.util.HashSet<>();
        for (Object selection : iterable) {
            if (selection == null) continue;
            SelectionHandles sel = SELECTION_HANDLES.get(selection.getClass());
            if (sel.getSource == null || sel.getGuid == null) continue;
            Object source = sel.getSource.invoke(selection);
            if (source != null && source.getClass().getName().equals(ARTMESH_SOURCE_CLASS)) {
                selected.add(String.valueOf(sel.getGuid.invoke(selection)));
            }
        }
        if (!selected.equals(previous)) {
            clearAxisForSelectionChange(state, Set.copyOf(selected));
        }
        return !selected.isEmpty();
    }

    private static void clearAxisForSelectionChange(State state, Set<String> nextSelection) {
        state.selectedMeshes = nextSelection;
        state.axis = null;
        state.drag = 0;
        state.vertexGesture = false;
        state.resetGesture = false;
        state.pairs.clear();
        state.written.clear();
        hideAngleLabel(state);
    }

    private static void initialize(Object view, State state) throws Throwable {
        ViewUiHandles handles = VIEW_UI_HANDLES.get(view.getClass());
        Object edit = handles.getCurrentEditMode.invokeExact(view);
        Object model = handles.getCurrentModel.invoke(edit);
        Object selections = handles.getSelected.invoke(handles.getSelector.invoke(edit));
        if (!(selections instanceof Iterable<?> list)) return;
        java.util.List<Object> forms = new java.util.ArrayList<>();
        for (Object selection : list) {
            if (selection == null) continue;
            SelectionHandles sel = SELECTION_HANDLES.get(selection.getClass());
            if (sel.getSource == null || sel.getGuid == null) continue;
            Object source = sel.getSource.invoke(selection);
            if (source == null || !source.getClass().getName().equals(ARTMESH_SOURCE_CLASS)) continue;
            Object guid = sel.getGuid.invoke(selection);
            if (guid == null) continue;
            MethodHandle getArtMesh = handles.getArtMeshForGuid(guid.getClass());
            Object mesh = getArtMesh.invoke(model, guid);
            if (mesh != null) forms.add(handles.getCalculatedForm.invoke(mesh));
        }
        state.axis = axisForCalculatedForms(forms, mode);
        if (state.axis != null) {
            Object camera = handles.getCameraManager.invokeExact(view);
            state.axis.setRotationRadius(
                    64 / projectAxis(handles, camera, state.axis).scale());
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
            if (state == null) return;
            ViewUiHandles handles = VIEW_UI_HANDLES.get(view.getClass());
            if (!syncSelectedMeshes(view, state, handles)) return;
            if (state.axis == null) initialize(view, state);
            if (state.axis == null) return;
            Object camera = handles.getCameraManager.invokeExact(view);
            if (!(boolean) handles.isSafeEditOnPlaneXY.invoke(camera)) return;
            DrawingResources resources = DRAWING.get(view.getClass());
            Object renderSystem = handles.getRenderSystem.invokeExact(view);
            Object drawer = handles.drawerOf.invoke(renderSystem);
            Object graph = handles.getSceneGraph.invokeExact(view);
            Object objectsOnComponent = handles.getObjectsOnComponent.invoke(graph);
            handles.drawerSetEntity.invoke(drawer, objectsOnComponent);
            handles.drawerSetLayer.invoke(drawer, resources.layer);
            ProjectedAxis projection = projectAxis(handles, camera, state.axis);
            var center = projection.center();
            var direction = projection.direction();
            Object rect = handles.getComponentRect.invoke(camera);
            float left = (float) handles.rectX.invoke(rect);
            float top = (float) handles.rectY.invoke(rect);
            float width = (float) handles.rectWidth.invoke(rect);
            float height = (float) handles.rectHeight.invoke(rect);
            float radius = (float) (Math.hypot(center.x() - left, center.y() - top) + Math.hypot(width, height));
            var start = new ArtMeshMirrorAxis.Point(
                    center.x() - direction.x() * radius, center.y() - direction.y() * radius);
            var end = new ArtMeshMirrorAxis.Point(
                    center.x() + direction.x() * radius, center.y() + direction.y() * radius);
            handles.drawerSetOrder.invoke(drawer, ORDER_AXIS_DARK);
            line(handles, drawer, start, end, resources.dark, AXIS_LINE_DARK_WIDTH);
            handles.drawerSetOrder.invoke(drawer, ORDER_AXIS_LIGHT);
            line(handles, drawer, start, end, resources.light, AXIS_LINE_LIGHT_WIDTH);
            marker(handles, drawer, center, resources, true);
            var handle = rotationHandle(projection, state.axis);
            marker(handles, drawer, handle, resources, false);
            long tenths = Math.round(Math.toDegrees(state.axis.angle()) * DEGREES_TO_TENTHS);
            if (state.angleLabel == null || state.labelTenths != tenths) {
                state.labelTenths = tenths;
                state.angleLabel = String.format(java.util.Locale.ROOT, "%.1f°", tenths / DEGREES_TO_TENTHS);
                state.labelDirty = true;
            }
            if (resources.labelHandles != null) {
                drawCachedAngleLabel(
                        state,
                        resources,
                        graph,
                        objectsOnComponent,
                        handle.x() + LABEL_OFFSET_X,
                        handle.y() + LABEL_OFFSET_Y);
            } else {
                // Fallback path for synthetic test hosts without GImageEntity.
                handles.drawerSetOrder.invoke(drawer, ORDER_LABEL_OUTLINE);
                for (int side = 0; side < 4; side++) {
                    float dx = side == 0 ? -1 : side == 1 ? 1 : 0;
                    float dy = side == 2 ? -1 : side == 3 ? 1 : 0;
                    angleText(
                            handles,
                            drawer,
                            state.angleLabel,
                            resources,
                            handle.x() + LABEL_OFFSET_X + dx,
                            handle.y() + LABEL_OFFSET_Y + dy,
                            resources.dark);
                }
                handles.drawerSetOrder.invoke(drawer, ORDER_LABEL_FILL);
                angleText(
                        handles,
                        drawer,
                        state.angleLabel,
                        resources,
                        handle.x() + LABEL_OFFSET_X,
                        handle.y() + LABEL_OFFSET_Y,
                        resources.light);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
        }
    }

    /** Returns true only when this tick already wrote this selected counterpart. */
    public static boolean move(Object ref, Object target, float weight) {
        if (!enabled() || APPLYING.get() || !Float.isFinite(weight) || weight <= 0) return false;
        State state = CURRENT.get();
        if (state == null || !state.vertexGesture || state.axis == null || state.movement == null || !dragCaller())
            return false;
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
            ArtMeshMirrorAxis.Point otherCurrent = read(call(other, "e"));
            if (state.movement.driver(
                            positions, index, counterpart, current.x(), current.y(), otherCurrent.x(), otherCurrent.y())
                    != index) return true;
            // Mirror the actual weighted displacement in canvas coordinates. Parent transforms
            // can be nonlinear and index-dependent, so blending in canvas space is not equivalent.
            Object transform = COUNTERPARTS.get(ref.getClass()).transform().get(ref);
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
            if (!COUNTERPARTS.get(ref.getClass()).isMeshPointRef(ref)) return;
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

    /**
     * Accepts only writes issued by a reviewed drag action.
     *
     * <p>Point dragging and brush movement use different, version-obfuscated action owners.
     * The active left-button gesture and main-modeling view gate writes; scripts, undo, and
     * non-action writes cannot enter this path. The walk stops at the first frame outside the
     * adapter, so a drag tick never materializes the whole stack.</p>
     */
    private static boolean dragCaller() {
        return STACK_WALKER.walk(frames -> frames.map(StackWalker.StackFrame::getClassName)
                .filter(name -> !name.startsWith(HOST_ADAPTER_PACKAGE))
                .filter(name -> !name.equals(THREAD_CLASS))
                .filter(name -> !name.equals(EX))
                .findFirst()
                .map(name -> name.startsWith(DRAG_ACTION_PACKAGE))
                .orElse(false));
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
        CounterpartHandles handles = COUNTERPARTS.get(ref.getClass());
        if (!handles.isMeshPointRef(ref)) throw new NoSuchMethodException(REF);
        Object source = call(ref, "d"), form = call(ref, "g"), coord = call(ref, "c");
        Object base = handles.baseConstructor(source.getClass(), coord.getClass())
                .newInstance(source, index, call(ref, "h"), call(ref, "i"), coord, form);
        return handles.exConstructor.newInstance(base, handles.transform().get(ref), form);
    }

    /**
     * Per-ref-class handles for counterpart construction.
     *
     * <p>The host resolves the point-ref classes, both constructors and the local-to-canvas
     * field once per class loader. A drag tick then only allocates host objects, so a per-point
     * write no longer pays for a class or constructor lookup. The entry is keyed by the ref
     * class, so an isolated host loader keeps its own handles and no host class outlives its
     * loader through this cache.</p>
     */
    private static final ClassValue<CounterpartHandles> COUNTERPARTS = new ClassValue<>() {
        @Override
        protected CounterpartHandles computeValue(Class<?> refType) {
            return CounterpartHandles.resolve(refType);
        }
    };

    private static final class CounterpartHandles {
        /** Marks a ref class that no reviewed host loader backs. */
        private static final CounterpartHandles UNAVAILABLE = new CounterpartHandles(null, null, null, null);

        private final Class<?> baseType;
        private final Class<?> formType;
        private final Field transform;
        final Constructor<?> exConstructor;
        private volatile BaseConstructor baseConstructor;

        private CounterpartHandles(
                Class<?> baseType, Class<?> formType, Field transform, Constructor<?> exConstructor) {
            this.baseType = baseType;
            this.formType = formType;
            this.transform = transform;
            this.exConstructor = exConstructor;
        }

        static CounterpartHandles resolve(Class<?> refType) {
            ClassLoader loader = refType.getClassLoader();
            try {
                Class<?> base = Class.forName(REF, false, loader);
                Class<?> form = Class.forName(ART_MESH_FORM_CLASS, false, loader);
                Class<?> transformType = Class.forName(TRANSFORM_CLASS, false, loader);
                Constructor<?> ex = Class.forName(EX, false, loader).getConstructor(base, transformType, form);
                return new CounterpartHandles(base, form, transformField(refType), ex);
            } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
                return UNAVAILABLE;
            }
        }

        private static Field transformField(Class<?> refType) {
            try {
                return ReflectAccess.open(MethodHandleCache.declaredFieldUp(refType, TRANSFORM_FIELD));
            } catch (NoSuchFieldException absent) {
                return null;
            }
        }

        boolean isMeshPointRef(Object ref) {
            return baseType != null && baseType.isInstance(ref);
        }

        Field transform() throws NoSuchFieldException {
            if (transform == null) throw new NoSuchFieldException(TRANSFORM_FIELD);
            return transform;
        }

        Constructor<?> baseConstructor(Class<?> sourceType, Class<?> coordinateType) throws NoSuchMethodException {
            BaseConstructor cached = baseConstructor;
            if (cached != null && cached.sourceType == sourceType && cached.coordinateType == coordinateType) {
                return cached.constructor;
            }
            if (baseType == null) throw new NoSuchMethodException(REF);
            Constructor<?> resolved =
                    baseType.getConstructor(sourceType, int.class, float[].class, int.class, coordinateType, formType);
            baseConstructor = new BaseConstructor(sourceType, coordinateType, resolved);
            return resolved;
        }

        private record BaseConstructor(Class<?> sourceType, Class<?> coordinateType, Constructor<?> constructor) {}
    }

    private record ProjectedAxis(ArtMeshMirrorAxis.Point center, ArtMeshMirrorAxis.Point direction, float scale) {}

    private static ProjectedAxis projectAxis(Object camera, Object view, ArtMeshMirrorAxis axis)
            throws ReflectiveOperationException {
        try {
            return projectAxis(VIEW_UI_HANDLES.get(view.getClass()), camera, axis);
        } catch (ReflectiveOperationException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException(failure);
        }
    }

    private static ProjectedAxis projectAxis(ViewUiHandles handles, Object camera, ArtMeshMirrorAxis axis)
            throws Throwable {
        var pivot = axis.pivot();
        var center = project(handles, camera, pivot);
        // A one-unit probe can round to the same screen pixel when zoomed out, or
        // to the same model float at large coordinates. Use a well-separated probe.
        float step = Math.max(1024, Math.max(Math.ulp(pivot.x()), Math.ulp(pivot.y())) * 64);
        var direction = axis.direction();
        var end = project(
                handles,
                camera,
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
        final Font awtFont;
        final LabelHandles labelHandles;

        DrawingResources(ClassLoader loader) throws ReflectiveOperationException {
            var color = Class.forName("com.live2d.type.CColor", false, loader)
                    .getConstructor(int.class, int.class, int.class);
            dark = color.newInstance(DARK_R, DARK_G, DARK_B);
            light = color.newInstance(LIGHT_RGB, LIGHT_RGB, LIGHT_RGB);
            font = Class.forName("com.live2d.type.CFont", false, loader)
                    .getConstructor(int.class)
                    .newInstance(LABEL_FONT_CTOR_SIZE);
            awtFont = resolveAwtFont(font);
            layer = Class.forName("com.live2d.graphics3d.component.a.c$b", false, loader)
                    .getField("a")
                    .get(null);
            labelHandles = LabelHandles.tryResolve(loader);
        }

        private static Font resolveAwtFont(Object cfont) {
            try {
                Method getJFont = cfont.getClass().getMethod("getJFont");
                if (getJFont.invoke(cfont) instanceof Font jfont) {
                    return jfont.deriveFont(LABEL_FONT_SIZE_PT);
                }
            } catch (ReflectiveOperationException ignored) {
                // Fall back to default sans-serif font when synthetic test fixtures omit getJFont.
            }
            return new Font(Font.SANS_SERIF, Font.PLAIN, (int) LABEL_FONT_SIZE_PT);
        }
    }

    private static final class AngleLabelWidget {
        final BufferedImage image;
        final Graphics2D graphics;
        final Object texture;
        final Object entity;

        AngleLabelWidget(LabelHandles handles, Object layer) throws Throwable {
            image = new BufferedImage(LABEL_IMAGE_WIDTH, LABEL_IMAGE_HEIGHT, BufferedImage.TYPE_INT_ARGB);
            graphics = image.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Object resource = handles.resourceCtor.invoke(image, true);
            texture = handles.textureCtor.invoke(resource, null, true);
            Object filterMode = handles.getFilterMode.invoke(texture);
            handles.setMinFilter.invoke(filterMode, handles.minFilterLinear);
            handles.setMagFilter.invoke(filterMode, handles.magFilterLinear);
            handles.setPreload.invoke(texture, true);
            Object initialBounds =
                    handles.rectCtor.invoke(0f, 0f, (float) LABEL_IMAGE_WIDTH, (float) LABEL_IMAGE_HEIGHT);
            entity = handles.imageEntityCtor.invoke("ArtMeshMirrorAngleLabel", texture, false, initialBounds);
            Object renderer = handles.getMeshRenderer.invoke(entity);
            handles.setSortingLayer.invoke(renderer, layer);
            handles.setOrderInLayer.invoke(renderer, ORDER_LABEL_FILL);
        }

        void updateText(String text, Font font, LabelHandles handles) throws Throwable {
            graphics.setComposite(AlphaComposite.Clear);
            graphics.fillRect(0, 0, LABEL_IMAGE_WIDTH, LABEL_IMAGE_HEIGHT);
            graphics.setComposite(AlphaComposite.SrcOver);
            graphics.setFont(font);
            graphics.setColor(LABEL_OUTLINE_COLOR);
            for (int side = 0; side < 4; side++) {
                int dx = side == 0 ? -1 : side == 1 ? 1 : 0;
                int dy = side == 2 ? -1 : side == 3 ? 1 : 0;
                graphics.drawString(text, LABEL_DRAW_X + dx, LABEL_DRAW_BASELINE_Y + dy);
            }
            graphics.setColor(LABEL_FILL_COLOR);
            graphics.drawString(text, LABEL_DRAW_X, LABEL_DRAW_BASELINE_Y);
            handles.reinitTexture.invoke(texture);
        }
    }

    private static void drawCachedAngleLabel(
            State state, DrawingResources resources, Object graph, Object objectsOnComponent, float x, float y)
            throws Throwable {
        LabelHandles handles = resources.labelHandles;
        if (state.angleWidget == null) {
            state.angleWidget = new AngleLabelWidget(handles, resources.layer);
            state.labelDirty = true;
        }
        AngleLabelWidget widget = state.angleWidget;
        if (state.labelDirty) {
            widget.updateText(state.angleLabel, resources.awtFont, handles);
            state.labelDirty = false;
        }
        Object transform = handles.getTransform.invoke(widget.entity);
        Object pos = handles.getPosition.invoke(transform);
        handles.setVector3.invoke(pos, x + LABEL_IMAGE_WIDTH * 0.5f, y + LABEL_IMAGE_HEIGHT * 0.5f, 0f);
        if (handles.getParentEntity.invoke(widget.entity) != objectsOnComponent) {
            Object children = handles.getChildren.invoke(objectsOnComponent);
            handles.addEntity.invoke(children, widget.entity, 0);
        }
        handles.setVolatile.invoke(graph, widget.entity);
    }

    private static void hideAngleLabel(State state) {
        if (state.angleWidget == null) return;
        try {
            LabelHandles handles = DRAWING.get(state.angleWidget.entity.getClass()).labelHandles;
            if (handles != null) {
                handles.setEnabled.invoke(state.angleWidget.entity, false);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static void angleText(
            ViewUiHandles handles,
            Object drawer,
            String text,
            DrawingResources resources,
            float x,
            float y,
            Object color)
            throws Throwable {
        handles.drawerText.invoke(drawer, text, resources.font, x, y, 0f, LABEL_FONT_SIZE_PT, color);
    }

    private static void marker(
            ViewUiHandles handles,
            Object drawer,
            ArtMeshMirrorAxis.Point center,
            DrawingResources resources,
            boolean diamond)
            throws Throwable {
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
            handles.drawerSetOrder.invoke(drawer, ORDER_MARKER_BASE + layer);
            float width = layer == 0 ? AXIS_LINE_DARK_WIDTH : AXIS_LINE_LIGHT_WIDTH;
            Object color = layer == 0 ? resources.dark : resources.light;
            for (int edge = 0; edge < 4; edge++) {
                line(handles, drawer, corners[edge], corners[(edge + 1) % 4], color, width);
            }
        }
    }

    private static ArtMeshMirrorAxis.Point project(ViewUiHandles handles, Object camera, ArtMeshMirrorAxis.Point point)
            throws Throwable {
        return read(
                handles, handles.documentToComponent.invoke(camera, handles.vectorCtor.invoke(point.x(), point.y())));
    }

    private static void line(
            ViewUiHandles handles,
            Object drawer,
            ArtMeshMirrorAxis.Point a,
            ArtMeshMirrorAxis.Point b,
            Object color,
            float width)
            throws Throwable {
        handles.drawerLine.invoke(
                drawer,
                handles.vectorCtor.invoke(a.x(), a.y()),
                handles.vectorCtor.invoke(b.x(), b.y()),
                color,
                width,
                0f);
    }

    private static double distance(ArtMeshMirrorAxis.Point a, ArtMeshMirrorAxis.Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static final ClassValue<Constructor<?>> VECTORS = new ClassValue<>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
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

    private static ArtMeshMirrorAxis.Point read(ViewUiHandles handles, Object vector) throws Throwable {
        return new ArtMeshMirrorAxis.Point(
                (float) handles.vectorX.invokeExact(vector), (float) handles.vectorY.invokeExact(vector));
    }

    private static final ClassValue<StateHandles> STATE_HANDLES = new ClassValue<>() {
        @Override
        protected StateHandles computeValue(Class<?> viewType) {
            try {
                return new StateHandles(viewType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final ClassValue<MethodHandle> MODEL_SOURCES = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> modelType) {
            try {
                return ViewUiHandles.unreflect(MethodHandles.lookup(), modelType, "getSource", 0);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class StateHandles {
        final MethodHandle getCurrentEditMode;
        final MethodHandle getCurrentModel;

        StateHandles(Class<?> viewType) throws ReflectiveOperationException {
            ClassLoader loader = viewType.getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getCurrentEditMode = ViewUiHandles.unreflect(lookup, viewType, "getCurrentEditMode", 0)
                    .asType(MethodType.methodType(Object.class, Object.class));
            Class<?> editType = Class.forName(MAIN_EDIT_MODE_CLASS, false, loader);
            getCurrentModel = ViewUiHandles.unreflect(lookup, editType, "getCurrentModel", 0);
        }

        MethodHandle getModelSource(Object model) {
            return MODEL_SOURCES.get(model.getClass());
        }
    }

    private static final ClassValue<EventHandles> EVENT_HANDLES = new ClassValue<>() {
        @Override
        protected EventHandles computeValue(Class<?> eventType) {
            return new EventHandles(eventType);
        }
    };

    private static final class EventHandles {
        final MethodHandle awt;
        final MethodHandle componentPoint;

        EventHandles(Class<?> eventType) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            awt = tryUnreflect(lookup, eventType, "f");
            componentPoint = tryUnreflect(lookup, eventType, "K");
        }

        private static MethodHandle tryUnreflect(MethodHandles.Lookup lookup, Class<?> type, String name) {
            try {
                return ViewUiHandles.unreflect(lookup, type, name, 0);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }

    private static final ClassValue<SelectionHandles> SELECTION_HANDLES = new ClassValue<>() {
        @Override
        protected SelectionHandles computeValue(Class<?> selectionType) {
            return new SelectionHandles(selectionType);
        }
    };

    private static final class SelectionHandles {
        final MethodHandle getSource;
        final MethodHandle getGuid;

        SelectionHandles(Class<?> selectionType) {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getSource = tryUnreflect(lookup, selectionType, "getSource");
            getGuid = tryUnreflect(lookup, selectionType, "getGuid");
        }

        private static MethodHandle tryUnreflect(MethodHandles.Lookup lookup, Class<?> type, String name) {
            try {
                return ViewUiHandles.unreflect(lookup, type, name, 0);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
    }

    private static final ClassValue<ViewUiHandles> VIEW_UI_HANDLES = new ClassValue<>() {
        @Override
        protected ViewUiHandles computeValue(Class<?> viewType) {
            try {
                return new ViewUiHandles(viewType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class ViewUiHandles {
        final MethodHandle getCurrentEditMode;
        final MethodHandle getCameraManager;
        final MethodHandle getRenderSystem;
        final MethodHandle getSceneGraph;
        final MethodHandle repaintCanvas;
        final MethodHandle getCurrentModel;
        final MethodHandle getSelector;
        final Class<?> modelType;
        final MethodHandle getArtMeshByGuid;
        final MethodHandle getArtMeshByString;
        final MethodHandle getCalculatedForm;
        final MethodHandle getSelected;
        final MethodHandle isSafeEditOnPlaneXY;
        final MethodHandle componentToDocument;
        final MethodHandle documentToComponent;
        final MethodHandle getComponentRect;
        final MethodHandle rectX;
        final MethodHandle rectY;
        final MethodHandle rectWidth;
        final MethodHandle rectHeight;
        final MethodHandle vectorCtor;
        final MethodHandle vectorX;
        final MethodHandle vectorY;
        final MethodHandle drawerOf;
        final MethodHandle getObjectsOnComponent;
        final MethodHandle drawerSetEntity;
        final MethodHandle drawerSetLayer;
        final MethodHandle drawerSetOrder;
        final MethodHandle drawerLine;
        final MethodHandle drawerText;

        ViewUiHandles(Class<?> viewType) throws ReflectiveOperationException {
            ClassLoader loader = viewType.getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getCurrentEditMode = unreflect(lookup, viewType, "getCurrentEditMode", 0)
                    .asType(MethodType.methodType(Object.class, Object.class));
            MethodHandle rawCamera = unreflect(lookup, viewType, "getCameraManager", 0);
            Class<?> cameraType = rawCamera.type().returnType();
            getCameraManager = rawCamera.asType(MethodType.methodType(Object.class, Object.class));

            MethodHandle rawRenderSystem = unreflect(lookup, viewType, "getRenderSystem", 0);
            Class<?> renderSystemType = rawRenderSystem.type().returnType();
            getRenderSystem = rawRenderSystem.asType(MethodType.methodType(Object.class, Object.class));

            MethodHandle rawSceneGraph = unreflect(lookup, viewType, "getSceneGraph", 0);
            Class<?> graphType = rawSceneGraph.type().returnType();
            getSceneGraph = rawSceneGraph.asType(MethodType.methodType(Object.class, Object.class));

            repaintCanvas = unreflect(lookup, viewType, "repaintCanvas", 0)
                    .asType(MethodType.methodType(void.class, Object.class));

            Class<?> editType = Class.forName(MAIN_EDIT_MODE_CLASS, false, loader);
            getCurrentModel = unreflect(lookup, editType, "getCurrentModel", 0);
            getSelector = unreflect(lookup, editType, "getSelector", 0);
            modelType = getCurrentModel.type().returnType();
            getArtMeshByGuid = findArtMeshLookup(lookup, modelType, false);
            getArtMeshByString = findArtMeshLookup(lookup, modelType, true);
            MethodHandle sampleArtMesh = getArtMeshByGuid != null ? getArtMeshByGuid : getArtMeshByString;
            if (sampleArtMesh == null) {
                throw new NoSuchMethodException(modelType.getName() + "#getArtMesh/1");
            }
            getCalculatedForm = unreflect(lookup, sampleArtMesh.type().returnType(), "getCalculatedForm", 0);

            Class<?> selectorType = getSelector.type().returnType();
            getSelected = unreflect(lookup, selectorType, "getSelected", 0);

            isSafeEditOnPlaneXY = unreflect(lookup, cameraType, "isSafeEditOnPlaneXY", 0);
            Class<?> cpointType = Class.forName("com.live2d.type.CPoint", false, loader);
            Class<?> vectorType = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);
            componentToDocument = unreflectExact(lookup, cameraType, "componentToDocument", cpointType);
            documentToComponent = unreflectExact(lookup, cameraType, "documentToComponent", vectorType);
            getComponentRect = unreflect(lookup, cameraType, "getComponentRect", 0);

            Class<?> rectType = getComponentRect.type().returnType();
            rectX = unreflect(lookup, rectType, "getX", 0).asType(MethodType.methodType(float.class, rectType));
            rectY = unreflect(lookup, rectType, "getY", 0).asType(MethodType.methodType(float.class, rectType));
            rectWidth = unreflect(lookup, rectType, "getWidth", 0).asType(MethodType.methodType(float.class, rectType));
            rectHeight =
                    unreflect(lookup, rectType, "getHeight", 0).asType(MethodType.methodType(float.class, rectType));

            Constructor<?> vCtor = vectorType.getConstructor(float.class, float.class);
            ReflectAccess.open(vCtor);
            vectorCtor = lookup.unreflectConstructor(vCtor);
            vectorX = unreflect(lookup, vectorType, "getX", 0).asType(MethodType.methodType(float.class, Object.class));
            vectorY = unreflect(lookup, vectorType, "getY", 0).asType(MethodType.methodType(float.class, Object.class));

            drawerOf = unreflect(lookup, renderSystemType, "b", 0);
            getObjectsOnComponent = unreflect(lookup, graphType, "getObjectsOnComponent", 0);

            Class<?> drawerType = drawerOf.type().returnType();
            drawerSetEntity = findDrawerSetter(lookup, drawerType, Object.class);
            drawerSetLayer = findDrawerLayerSetter(lookup, drawerType, loader);
            drawerSetOrder = unreflectExact(lookup, drawerType, "a", int.class);
            drawerLine = findDrawerLine(lookup, drawerType, vectorType, loader);
            drawerText = findDrawerText(lookup, drawerType, loader);
        }

        MethodHandle getArtMeshForGuid(Class<?> guidType) throws NoSuchMethodException {
            if (guidType == String.class && getArtMeshByString != null) {
                return getArtMeshByString;
            }
            if (getArtMeshByGuid != null) {
                return getArtMeshByGuid;
            }
            if (getArtMeshByString != null) {
                return getArtMeshByString;
            }
            throw new NoSuchMethodException(modelType.getName() + "#getArtMesh(" + guidType.getName() + ")");
        }

        private static MethodHandle findArtMeshLookup(
                MethodHandles.Lookup lookup, Class<?> modelType, boolean stringOnly)
                throws ReflectiveOperationException {
            for (Method method : MethodHandleCache.overloads(modelType, "getArtMesh", 1)) {
                Class<?> param = method.getParameterTypes()[0];
                if (stringOnly && param == String.class) {
                    ReflectAccess.open(method);
                    return lookup.unreflect(method);
                }
                if (!stringOnly && param != String.class && param.getName().endsWith("Guid")) {
                    ReflectAccess.open(method);
                    return lookup.unreflect(method);
                }
            }
            return null;
        }

        private static MethodHandle unreflect(MethodHandles.Lookup lookup, Class<?> owner, String name, int arity)
                throws ReflectiveOperationException {
            for (Method method : MethodHandleCache.overloads(owner, name, arity)) {
                if (method.isBridge() || method.isSynthetic()) continue;
                ReflectAccess.open(method);
                return lookup.unreflect(method);
            }
            throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + arity);
        }

        private static MethodHandle unreflectExact(
                MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?>... params)
                throws ReflectiveOperationException {
            Method method = owner.getMethod(name, params);
            ReflectAccess.open(method);
            return lookup.unreflect(method);
        }

        private static MethodHandle findDrawerSetter(
                MethodHandles.Lookup lookup, Class<?> drawerType, Class<?> fallback)
                throws ReflectiveOperationException {
            for (Method method : MethodHandleCache.overloads(drawerType, "a", 1)) {
                Class<?> param = method.getParameterTypes()[0];
                if (param.getName().endsWith("GEntity") || param == fallback) {
                    ReflectAccess.open(method);
                    return lookup.unreflect(method);
                }
            }
            throw new NoSuchMethodException(drawerType.getName() + "#a(GEntity)");
        }

        private static MethodHandle findDrawerLayerSetter(
                MethodHandles.Lookup lookup, Class<?> drawerType, ClassLoader loader)
                throws ReflectiveOperationException {
            try {
                Class<?> layerType = Class.forName("com.live2d.graphics3d.component.a.c", false, loader);
                return unreflectExact(lookup, drawerType, "a", layerType);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                return findDrawerSetter(lookup, drawerType, Object.class);
            }
        }

        private static MethodHandle findDrawerLine(
                MethodHandles.Lookup lookup, Class<?> drawerType, Class<?> vectorType, ClassLoader loader)
                throws ReflectiveOperationException {
            Class<?> colorType = Class.forName("com.live2d.type.CColor", false, loader);
            return unreflectExact(lookup, drawerType, "a", vectorType, vectorType, colorType, float.class, float.class);
        }

        private static MethodHandle findDrawerText(MethodHandles.Lookup lookup, Class<?> drawerType, ClassLoader loader)
                throws ReflectiveOperationException {
            Class<?> fontType = Class.forName("com.live2d.type.CFont", false, loader);
            Class<?> colorType = Class.forName("com.live2d.type.CColor", false, loader);
            return unreflectExact(
                    lookup,
                    drawerType,
                    "a",
                    String.class,
                    fontType,
                    float.class,
                    float.class,
                    float.class,
                    float.class,
                    colorType);
        }
    }

    private static final class LabelHandles {
        final MethodHandle resourceCtor;
        final MethodHandle textureCtor;
        final MethodHandle getFilterMode;
        final MethodHandle setMinFilter;
        final MethodHandle setMagFilter;
        final MethodHandle setPreload;
        final MethodHandle reinitTexture;
        final MethodHandle rectCtor;
        final MethodHandle imageEntityCtor;
        final MethodHandle getMeshRenderer;
        final MethodHandle setSortingLayer;
        final MethodHandle setOrderInLayer;
        final MethodHandle getTransform;
        final MethodHandle getPosition;
        final MethodHandle setVector3;
        final MethodHandle getParentEntity;
        final MethodHandle getChildren;
        final MethodHandle addEntity;
        final MethodHandle setVolatile;
        final MethodHandle setEnabled;
        final Object minFilterLinear;
        final Object magFilterLinear;

        private LabelHandles(ClassLoader loader) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> resourceClass = Class.forName("com.live2d.graphics.CImageResource", false, loader);
            Constructor<?> rCtor = resourceClass.getConstructor(BufferedImage.class, boolean.class);
            ReflectAccess.open(rCtor);
            resourceCtor = lookup.unreflectConstructor(rCtor);

            Class<?> affineClass = Class.forName("com.live2d.type.CAffine", false, loader);
            Class<?> texture2DClass = Class.forName("com.live2d.graphics3d.texture.GTexture2D", false, loader);
            Constructor<?> tCtor = texture2DClass.getConstructor(resourceClass, affineClass, boolean.class);
            ReflectAccess.open(tCtor);
            textureCtor = lookup.unreflectConstructor(tCtor);

            Class<?> textureClass = Class.forName("com.live2d.graphics3d.texture.GTexture", false, loader);
            getFilterMode = unreflect(lookup, textureClass, "getFilterMode");
            Class<?> filterModeClass = getFilterMode.type().returnType();
            Class<?> minFilterClass = Class.forName("com.live2d.graphics3d.texture.MinFilter", false, loader);
            Class<?> magFilterClass = Class.forName("com.live2d.graphics3d.texture.MagFilter", false, loader);
            setMinFilter = unreflect(lookup, filterModeClass, "setMinFilter", minFilterClass);
            setMagFilter = unreflect(lookup, filterModeClass, "setMagFilter", magFilterClass);
            setPreload = unreflect(lookup, textureClass, "setPreload", boolean.class);
            reinitTexture = unreflect(lookup, textureClass, "reinitTexture");
            minFilterLinear = minFilterClass.getField("LINEAR").get(null);
            magFilterLinear = magFilterClass.getField("LINEAR").get(null);

            Class<?> rectClass = Class.forName("com.live2d.graphics3d.type.GRectF", false, loader);
            Constructor<?> rectC = rectClass.getConstructor(float.class, float.class, float.class, float.class);
            ReflectAccess.open(rectC);
            rectCtor = lookup.unreflectConstructor(rectC);

            Class<?> imageEntityClass = Class.forName("com.live2d.graphics3d.geometry.GImageEntity", false, loader);
            Constructor<?> ieCtor =
                    imageEntityClass.getConstructor(String.class, textureClass, boolean.class, rectClass);
            ReflectAccess.open(ieCtor);
            imageEntityCtor = lookup.unreflectConstructor(ieCtor);

            getMeshRenderer = unreflect(lookup, imageEntityClass, "getMeshRenderer");
            Class<?> rendererClass = getMeshRenderer.type().returnType();
            Class<?> layerClass = Class.forName("com.live2d.graphics3d.component.a.c", false, loader);
            setSortingLayer = unreflect(lookup, rendererClass, "setSortingLayer", layerClass);
            setOrderInLayer = unreflect(lookup, rendererClass, "setOrderInLayer", int.class);

            Class<?> entityClass = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
            getTransform = unreflect(lookup, entityClass, "getTransform");
            Class<?> transformClass = getTransform.type().returnType();
            getPosition = unreflect(lookup, transformClass, "getPosition");
            Class<?> vec3Class = getPosition.type().returnType();
            setVector3 = unreflect(lookup, vec3Class, "set", float.class, float.class, float.class);
            getParentEntity = unreflect(lookup, entityClass, "getParentEntity");
            getChildren = unreflect(lookup, entityClass, "getChildren");
            Class<?> entitiesClass = getChildren.type().returnType();
            addEntity = unreflect(lookup, entitiesClass, "add", entityClass, int.class);
            setEnabled = unreflect(lookup, entityClass, "setEnabled", boolean.class);

            Class<?> sceneGraphClass = Class.forName("com.live2d.graphics3d.sceneGraph.GSceneGraph", false, loader);
            setVolatile = unreflect(lookup, sceneGraphClass, "setVolatile", entityClass);
        }

        static LabelHandles tryResolve(ClassLoader loader) {
            try {
                return new LabelHandles(loader);
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }

        private static MethodHandle unreflect(
                MethodHandles.Lookup lookup, Class<?> owner, String name, Class<?>... params)
                throws ReflectiveOperationException {
            Method method = owner.getMethod(name, params);
            ReflectAccess.open(method);
            return lookup.unreflect(method);
        }
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
