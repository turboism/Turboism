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
import java.nio.file.Path;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
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
    private static final String GVECTOR2_CLASS = "com.live2d.graphics3d.type.GVector2";
    private static final String CPOINT_CLASS = "com.live2d.type.CPoint";
    private static final String CCOLOR_CLASS = "com.live2d.type.CColor";
    private static final String CFONT_CLASS = "com.live2d.type.CFont";
    private static final String LAYER_ENUM_CLASS = "com.live2d.graphics3d.component.a.c$b";
    private static final String LAYER_BASE_CLASS = "com.live2d.graphics3d.component.a.c";
    private static final String DRAG_ACTION_PACKAGE = "com.live2d.cubism.view.context.action.";
    private static final String HOST_ADAPTER_PACKAGE = "dev.turboism.";
    private static final String THREAD_CLASS = "java.lang.Thread";
    private static final double DEGREES_TO_TENTHS = 10.0;
    private static final float AXIS_LINE_DARK_WIDTH = 4.0f;
    private static final float AXIS_LINE_LIGHT_WIDTH = 1.5f;
    private static final int ORDER_AXIS_DARK = 90;
    private static final int ORDER_AXIS_LIGHT = 91;
    private static final int ORDER_MARKER_BASE = 92;
    private static final int ORDER_CENTER_MARKER = 94;
    private static final int ORDER_BUTTON = 96;
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
    private static final int MUTED_DARK_R = 96;
    private static final int MUTED_DARK_G = 96;
    private static final int MUTED_DARK_B = 104;
    private static final int MUTED_LIGHT_R = 196;
    private static final int MUTED_LIGHT_G = 196;
    private static final int MUTED_LIGHT_B = 204;
    private static final int MAX_MESH_VERTICES = 10_000;
    private static final float SCREEN_PAIR_TOLERANCE_PX = 20.0f;
    private static final float HANDLE_HIT_RADIUS_PX = 12.0f;
    private static final float CENTER_HIT_RADIUS_PX = 11.0f;
    private static final float LINE_HIT_DISTANCE_PX = 5.0f;
    private static final float LOCK_BUTTON_OFFSET_PX = 64.0f;
    private static final float RESET_BUTTON_OFFSET_PX = 96.0f;
    private static final float BUTTON_HIT_RADIUS_PX = 12.0f;
    private static final float MARKER_DIAMOND_RADIUS_PX = 9.0f;
    private static final float MARKER_SQUARE_RADIUS_PX = 8.0f;
    private static final float PROBE_MIN_STEP = 1024.0f;
    private static final float PROBE_ULP_MULTIPLIER = 64.0f;
    private static final float ROTATION_ARM_MIN_PX = 32.0f;
    private static final float DEFAULT_ROTATION_RADIUS = 64.0f;
    private static final String CENTER_HANDLE_ENTITY_NAME = "ArtMeshMirrorCenterHandle";
    private static final String CENTER_HANDLE_LOCKED_ENTITY_NAME = "ArtMeshMirrorCenterHandleLocked";
    private static final String UNLOCK_BUTTON_ENTITY_NAME = "ArtMeshMirrorUnlockButton";
    private static final String LOCK_BUTTON_ENTITY_NAME = "ArtMeshMirrorLockButton";
    private static final String RESET_BUTTON_ENTITY_NAME = "ArtMeshMirrorResetButton";
    private static final String RESET_DISABLED_BUTTON_ENTITY_NAME = "ArtMeshMirrorResetDisabledButton";
    private static final String FALLBACK_LOCKED_GLYPH = "L";
    private static final String FALLBACK_UNLOCKED_GLYPH = "U";
    private static final String FALLBACK_RESET_GLYPH = "R";
    private static final MethodType OBJECT_GETTER_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType OBJECT_ARG_OBJECT_TYPE =
            MethodType.methodType(Object.class, Object.class, Object.class);
    private static final MethodType FLOAT_GETTER_TYPE = MethodType.methodType(float.class, Object.class);
    private static final MethodType INT_GETTER_TYPE = MethodType.methodType(int.class, Object.class);
    private static final MethodType BOOLEAN_GETTER_TYPE = MethodType.methodType(boolean.class, Object.class);
    private static final MethodType VOID_OBJECT_TYPE = MethodType.methodType(void.class, Object.class);
    private static final Color LABEL_OUTLINE_COLOR = new Color(DARK_R, DARK_G, DARK_B);
    private static final Color LABEL_FILL_COLOR = new Color(LIGHT_RGB, LIGHT_RGB, LIGHT_RGB);
    private static final StackWalker STACK_WALKER = createStackWalker();
    private static final Map<Object, State> STATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> APPLYING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<ScratchVectors> SCRATCH_VECTORS = new ThreadLocal<>();
    private static final Set<String> REPORTED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static volatile int mode;
    private static volatile ArtMeshMirrorAxisStore axisStore;
    private static final RuntimeArtMeshMirrorService SERVICE = new RuntimeArtMeshMirrorService();

    /** Returns the participation service for independent ArtMesh mirror editing. */
    public static RuntimeArtMeshMirrorService service() {
        return SERVICE;
    }

    /** Binds the runtime directory used to persist per-ArtMesh mirror axis states across restarts. */
    public static void bindPersistenceDirectory(Path runtimeDataDir) {
        ArtMeshMirrorAxisStore previous = axisStore;
        axisStore = runtimeDataDir == null ? null : ArtMeshMirrorAxisStore.at(runtimeDataDir);
        if (previous != null) {
            previous.close();
        }
    }

    /** Flushes pending mirror axis writes and detaches the persistence store on shutdown. */
    public static void flushAndUnbindPersistence() {
        ArtMeshMirrorAxisStore previous = axisStore;
        axisStore = null;
        if (previous != null) {
            previous.close();
        }
    }

    static boolean isEnabled() {
        return mode != 0;
    }

    static void resetAxes() {
        synchronized (STATES) {
            for (State state : STATES.values()) {
                clearPersistedMeshAxis(state);
                state.locked = false;
                state.axis = null;
                state.drag = 0;
                state.vertexGesture = false;
                state.consumedGesture = false;
                state.pairs.clear();
                state.written.clear();
                state.writtenBits.clear();
                hideOverlayWidgets(state);
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
        final String modelGuid;
        final Map<String, ArtMeshMirrorAxisStore.SavedAxis> sessionAxes = new HashMap<>();
        java.lang.ref.WeakReference<Object> view;
        ArtMeshMirrorAxis axis;
        Set<String> selectedMeshes = Set.of();
        long labelTenths = Long.MIN_VALUE;
        String angleLabel;
        boolean labelDirty;
        AngleLabelWidget angleWidget;
        IconButtonWidget centerHandleWidget;
        IconButtonWidget centerHandleLockedWidget;
        IconButtonWidget unlockedButtonWidget;
        IconButtonWidget lockedButtonWidget;
        IconButtonWidget resetEnabledButtonWidget;
        IconButtonWidget resetDisabledButtonWidget;
        boolean locked;
        int drag;
        boolean vertexGesture;
        /**
         * Set by a non-drag axis click (lock toggle, reset button, or double-click reset). The
         * matching drag and release must be consumed as well so they never reach the native
         * dispatcher and start an unintended mesh edit.
         */
        boolean consumedGesture;

        NativeWarpPointGesture.Gesture movement;
        ArtMeshMirrorAxis.Point last;
        final Map<float[], Pairing> pairs = new IdentityHashMap<>();
        final Map<float[], Set<Integer>> written = new IdentityHashMap<>();
        final Map<float[], BitSet> writtenBits = new IdentityHashMap<>();

        State() {
            this(null);
        }

        State(String modelGuid) {
            this.modelGuid = modelGuid;
        }
    }

    private record Pairing(int[] counterparts) {}

    private NativeArtMeshSymmetryBridge() {}

    static void setMode(int next) {
        mode = next;
        synchronized (STATES) {
            for (State state : STATES.values()) {
                state.drag = 0;
                state.vertexGesture = false;
                state.consumedGesture = false;
                state.pairs.clear();
                state.written.clear();
                state.writtenBits.clear();
                if (next == 0) hideOverlayWidgets(state);
            }
        }
        if (next == 0) CURRENT.remove();
    }

    static void reset() {
        setMode(0);
        STATES.clear();
        CURRENT.remove();
        APPLYING.remove();
        SCRATCH_VECTORS.remove();
        REPORTED.clear();
        flushAndUnbindPersistence();
    }

    private static boolean enabled() {
        return mode != 0 && NativeWarpAltMirrorBridge.active() && SERVICE.hasParticipants();
    }

    private static State state(Object view) throws Throwable {
        if (!enabled() || view == null) return null;
        StateHandles handles = STATE_HANDLES.get(view.getClass());
        if (!handles.isModelingView) return null;
        Object edit = handles.getCurrentEditMode.invokeExact(view);
        // Exclude form-animation and mesh-topology editors, which own different write policies.
        if (edit == null
                || (edit.getClass() != handles.editType
                        && !edit.getClass().getName().equals(MAIN_EDIT_MODE_CLASS))) {
            return null;
        }
        Object model = handles.getCurrentModel.invokeExact(edit);
        Object source = handles.getModelSource(model).invokeExact(model);
        synchronized (STATES) {
            State state = STATES.get(source);
            if (state == null) {
                state = new State(resolveModelGuid(source));
                STATES.put(source, state);
            }
            if (state.view == null || state.view.get() != view) {
                state.view = new java.lang.ref.WeakReference<>(view);
            }
            return state;
        }
    }

    /** Called before native input dispatch. Only a hit on the axis controls captures a gesture. */
    public static boolean input(Object view, Object event) {
        try {
            if (!enabled() || view == null || event == null) {
                CURRENT.remove();
                return false;
            }
            EventHandles eventHandles = EVENT_HANDLES.get(event.getClass());
            if (eventHandles.awt == null) {
                CURRENT.remove();
                return false;
            }
            Object awt = eventHandles.awt.invokeExact(event);
            if (!(awt instanceof MouseEvent mouse)) {
                CURRENT.remove();
                return false;
            }
            int id = mouse.getID();
            if (id != MouseEvent.MOUSE_PRESSED && id != MouseEvent.MOUSE_DRAGGED && id != MouseEvent.MOUSE_RELEASED) {
                CURRENT.remove();
                return false;
            }
            State state = state(view);
            CURRENT.remove();
            if (state == null) return false;
            if (id == MouseEvent.MOUSE_DRAGGED
                    && state.drag == 0
                    && !state.consumedGesture
                    && state.axis != null
                    && !state.selectedMeshes.isEmpty()) {
                CURRENT.set(state);
                return false;
            }
            ViewUiHandles handles = VIEW_UI_HANDLES.get(view.getClass());
            // Skip selection polling while actively dragging or finishing a consumed button gesture.
            if (!((state.drag != 0 || state.consumedGesture) && id == MouseEvent.MOUSE_DRAGGED)) {
                if (!syncSelectedMeshes(view, state, handles)) {
                    state.drag = 0;
                    state.consumedGesture = false;
                    return false;
                }
            }
            CURRENT.set(state);
            if (id == MouseEvent.MOUSE_PRESSED) {
                state.movement = null;
                state.vertexGesture = mouse.getButton() == MouseEvent.BUTTON1;
                state.drag = 0;
                state.consumedGesture = false;
                state.pairs.clear();
                state.written.clear();
                clearWrittenBits(state);
            }
            if (state.axis == null) initialize(view, state);
            if (state.axis == null) return false;
            if (id == MouseEvent.MOUSE_DRAGGED && state.consumedGesture) {
                return true;
            }
            boolean needsPoint = id == MouseEvent.MOUSE_PRESSED || (state.drag != 0 && id == MouseEvent.MOUSE_DRAGGED);
            if (!needsPoint) {
                if (id == MouseEvent.MOUSE_RELEASED) {
                    state.movement = null;
                    state.vertexGesture = false;
                    boolean wasAxisDrag = state.drag != 0;
                    boolean captured = wasAxisDrag || state.consumedGesture;
                    if (wasAxisDrag && state.axis != null) {
                        persistCurrentMeshAxis(state);
                    }
                    state.drag = 0;
                    state.consumedGesture = false;
                    state.pairs.clear();
                    state.written.clear();
                    clearWrittenBits(state);
                    if (captured) {
                        handles.repaintCanvas.invokeExact(view);
                        return true;
                    }
                }
                return false;
            }
            Object camera = handles.getCameraManager.invokeExact(view);
            if (!(boolean) handles.isSafeEditOnPlaneXY.invokeExact(camera)) return false;
            if (eventHandles.componentPoint == null) return false;
            Object eventPoint = eventHandles.componentPoint.invokeExact(event);
            ArtMeshMirrorAxis.Point point = read(handles, handles.componentToDocument.invokeExact(camera, eventPoint));
            if (id == MouseEvent.MOUSE_PRESSED && mouse.getButton() == MouseEvent.BUTTON1)
                state.movement = new NativeWarpPointGesture.Gesture(view, point.x(), point.y());
            if (id == MouseEvent.MOUSE_PRESSED
                    && mouse.getButton() == MouseEvent.BUTTON1
                    && !mouse.isAltDown()
                    && !mouse.isControlDown()
                    && !mouse.isMetaDown()) {
                ArtMeshMirrorAxis.Point screen = project(handles, camera, point);
                ProjectedAxis projection = projectAxis(handles, camera, state.axis);
                if (distance(screen, lockButtonPosition(projection)) <= BUTTON_HIT_RADIUS_PX) {
                    state.locked = !state.locked;
                    persistCurrentMeshAxis(state);
                    state.vertexGesture = false;
                    state.consumedGesture = true;
                    handles.repaintCanvas.invokeExact(view);
                    return true;
                }
                if (state.locked) {
                    return false;
                }
                if (shouldShowResetButton(state.axis)
                        && distance(screen, resetButtonPosition(projection)) <= BUTTON_HIT_RADIUS_PX) {
                    resetAxisState(view, state);
                    state.vertexGesture = false;
                    state.consumedGesture = true;
                    handles.repaintCanvas.invokeExact(view);
                    return true;
                }
                ArtMeshMirrorAxis.Point center = projection.center();
                ArtMeshMirrorAxis.Point handle = rotationHandle(projection, state.axis);
                if (distance(screen, handle) <= HANDLE_HIT_RADIUS_PX) state.drag = 3;
                else if (distance(screen, center) <= CENTER_HIT_RADIUS_PX) state.drag = 2;
                else {
                    float dx = projection.direction().x();
                    float dy = projection.direction().y();
                    double length = Math.hypot(dx, dy);
                    double perpendicular =
                            Math.abs((screen.x() - center.x()) * dy - (screen.y() - center.y()) * dx) / length;
                    if (perpendicular <= LINE_HIT_DISTANCE_PX) state.drag = 1;
                }
                if (state.drag != 0) state.vertexGesture = false;
                state.last = point;
                if (state.drag != 0 && mouse.getClickCount() == 2) {
                    resetAxisState(view, state);
                    state.drag = 0;
                    state.consumedGesture = true;
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
                if (!state.written.isEmpty()) state.written.clear();
                clearWrittenBits(state);
                CURRENT.set(state);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(failure);
            CURRENT.remove();
        }
    }

    private static void clearWrittenBits(State state) {
        if (!state.writtenBits.isEmpty()) {
            for (BitSet bits : state.writtenBits.values()) {
                bits.clear();
            }
        }
    }

    private static boolean syncSelectedMeshes(Object view, State state, ViewUiHandles handles) throws Throwable {
        Object edit = handles.getCurrentEditMode.invokeExact(view);
        Object selector = handles.getSelector.invokeExact(edit);
        Object selectedList = handles.getSelected.invokeExact(selector);
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
            Object source = sel.getSource.invokeExact(selection);
            if (source != null && ART_MESH_SOURCE_TYPES.get(source.getClass())) {
                String guid = extractGuidString(sel.getGuid.invokeExact(selection));
                if (guid == null) continue;
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
            Object source = sel.getSource.invokeExact(selection);
            if (source != null && ART_MESH_SOURCE_TYPES.get(source.getClass())) {
                String guid = extractGuidString(sel.getGuid.invokeExact(selection));
                if (guid != null) selected.add(guid);
            }
        }
        if (!selected.equals(previous)) {
            clearAxisForSelectionChange(state, Set.copyOf(selected));
        }
        return !selected.isEmpty();
    }

    private static void clearAxisForSelectionChange(State state, Set<String> nextSelection) {
        if (state.drag != 0 && state.axis != null) {
            persistCurrentMeshAxis(state);
        }
        state.selectedMeshes = nextSelection;
        state.locked = false;
        state.axis = null;
        state.drag = 0;
        state.vertexGesture = false;
        state.consumedGesture = false;
        state.pairs.clear();
        state.written.clear();
        state.writtenBits.clear();
        hideOverlayWidgets(state);
    }

    private static void resetAxisState(Object view, State state) throws Throwable {
        clearPersistedMeshAxis(state);
        state.locked = false;
        state.axis = null;
        state.pairs.clear();
        initialize(view, state);
    }

    private static void initialize(Object view, State state) throws Throwable {
        ViewUiHandles handles = VIEW_UI_HANDLES.get(view.getClass());
        Object edit = handles.getCurrentEditMode.invokeExact(view);
        Object model = handles.getCurrentModel.invokeExact(edit);
        Object selections = handles.getSelected.invokeExact(handles.getSelector.invokeExact(edit));
        if (!(selections instanceof Iterable<?> list)) return;
        java.util.List<Object> forms = new java.util.ArrayList<>();
        for (Object selection : list) {
            if (selection == null) continue;
            SelectionHandles sel = SELECTION_HANDLES.get(selection.getClass());
            if (sel.getSource == null || sel.getGuid == null) continue;
            Object source = sel.getSource.invokeExact(selection);
            if (source == null || !ART_MESH_SOURCE_TYPES.get(source.getClass())) continue;
            Object guid = sel.getGuid.invokeExact(selection);
            if (guid == null) continue;
            MethodHandle getArtMesh = handles.getArtMeshForGuid(guid.getClass());
            Object mesh = getArtMesh.invokeExact(model, guid);
            if (mesh != null) forms.add(handles.getCalculatedForm.invokeExact(mesh));
        }
        ArtMeshMirrorAxis defaultAxis = axisForCalculatedForms(forms, mode);
        state.locked = false;
        if (defaultAxis != null) {
            String meshGuid = singleSelectedMeshGuid(state);
            if (meshGuid != null) {
                ArtMeshMirrorAxisStore.SavedAxis saved = loadSavedAxis(state, meshGuid);
                if (saved != null) {
                    defaultAxis.restore(new ArtMeshMirrorAxis.Point(saved.pivotX(), saved.pivotY()), saved.angle());
                    state.locked = saved.locked();
                }
            }
            Object camera = handles.getCameraManager.invokeExact(view);
            defaultAxis.setRotationRadius(DEFAULT_ROTATION_RADIUS
                    / projectAxis(handles, camera, defaultAxis).scale());
        }
        state.axis = defaultAxis;
    }

    private static String singleSelectedMeshGuid(State state) {
        if (state.selectedMeshes.size() != 1) {
            return null;
        }
        return state.selectedMeshes.iterator().next();
    }

    private static ArtMeshMirrorAxisStore.SavedAxis loadSavedAxis(State state, String meshGuid) {
        ArtMeshMirrorAxisStore.SavedAxis sessionSaved = state.sessionAxes.get(meshGuid);
        if (sessionSaved != null) {
            return sessionSaved;
        }
        ArtMeshMirrorAxisStore store = axisStore;
        if (store == null || state.modelGuid == null) {
            return null;
        }
        ArtMeshMirrorAxisStore.SavedAxis diskSaved = store.get(state.modelGuid, meshGuid);
        if (diskSaved != null) {
            state.sessionAxes.put(meshGuid, diskSaved);
        }
        return diskSaved;
    }

    private static void persistCurrentMeshAxis(State state) {
        if (state.axis == null) {
            return;
        }
        String meshGuid = singleSelectedMeshGuid(state);
        if (meshGuid == null) {
            return;
        }
        if (state.axis.isAtInitialState() && !state.locked) {
            clearPersistedMeshAxis(state);
            return;
        }
        ArtMeshMirrorAxis.Point pivot = state.axis.pivot();
        ArtMeshMirrorAxisStore.SavedAxis saved =
                new ArtMeshMirrorAxisStore.SavedAxis(pivot.x(), pivot.y(), state.axis.angle(), state.locked);
        state.sessionAxes.put(meshGuid, saved);
        ArtMeshMirrorAxisStore store = axisStore;
        if (store != null && state.modelGuid != null) {
            store.put(state.modelGuid, meshGuid, saved);
        }
    }

    private static void clearPersistedMeshAxis(State state) {
        String meshGuid = singleSelectedMeshGuid(state);
        if (meshGuid == null) {
            return;
        }
        state.sessionAxes.remove(meshGuid);
        ArtMeshMirrorAxisStore store = axisStore;
        if (store != null && state.modelGuid != null) {
            store.remove(state.modelGuid, meshGuid);
        }
    }

    static ArtMeshMirrorAxis axisForCalculatedForms(Iterable<?> forms, int axisMode)
            throws ReflectiveOperationException {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        try {
            for (Object form : forms) {
                if (form == null) continue;
                // Calculated forms already contain canvas coordinates. Applying the model's
                // local-to-canvas transform again would push the pivot far outside the viewport.
                float[] positions =
                        (float[]) FORM_POSITIONS.get(form.getClass()).invokeExact(form);
                for (int i = 0; i + 1 < positions.length; i += 2) {
                    minX = Math.min(minX, positions[i]);
                    maxX = Math.max(maxX, positions[i]);
                    minY = Math.min(minY, positions[i + 1]);
                    maxY = Math.max(maxY, positions[i + 1]);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException(failure);
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
            if (!(boolean) handles.isSafeEditOnPlaneXY.invokeExact(camera)) return;
            DrawingResources resources = DRAWING.get(view.getClass());
            Object renderSystem = handles.getRenderSystem.invokeExact(view);
            Object drawer = handles.drawerOf.invokeExact(renderSystem);
            Object graph = handles.getSceneGraph.invokeExact(view);
            Object objectsOnComponent = handles.getObjectsOnComponent.invokeExact(graph);
            handles.drawerSetEntity.invokeExact(drawer, objectsOnComponent);
            handles.drawerSetLayer.invokeExact(drawer, resources.layer);
            ProjectedAxis projection = projectAxis(handles, camera, state.axis);
            var center = projection.center();
            var direction = projection.direction();
            Object rect = handles.getComponentRect.invokeExact(camera);
            float left = (float) handles.rectX.invokeExact(rect);
            float top = (float) handles.rectY.invokeExact(rect);
            float width = (float) handles.rectWidth.invokeExact(rect);
            float height = (float) handles.rectHeight.invokeExact(rect);
            float radius = (float) (Math.hypot(center.x() - left, center.y() - top) + Math.hypot(width, height));
            float startX = center.x() - direction.x() * radius;
            float startY = center.y() - direction.y() * radius;
            float endX = center.x() + direction.x() * radius;
            float endY = center.y() + direction.y() * radius;
            handles.drawerSetOrder.invokeExact(drawer, ORDER_AXIS_DARK);
            line(handles, drawer, startX, startY, endX, endY, resources.dark, AXIS_LINE_DARK_WIDTH);
            handles.drawerSetOrder.invokeExact(drawer, ORDER_AXIS_LIGHT);
            line(handles, drawer, startX, startY, endX, endY, resources.light, AXIS_LINE_LIGHT_WIDTH);
            Object markerDark = state.locked ? resources.mutedDark : resources.dark;
            Object markerLight = state.locked ? resources.mutedLight : resources.light;
            var handle = rotationHandle(projection, state.axis);
            marker(handles, drawer, handle.x(), handle.y(), markerDark, markerLight, false);
            ArtMeshMirrorAxis.Point lockPos = lockButtonPosition(projection);
            ArtMeshMirrorAxis.Point resetPos = resetButtonPosition(projection);
            boolean showReset = shouldShowResetButton(state.axis);
            long tenths = Math.round(Math.toDegrees(state.axis.angle()) * DEGREES_TO_TENTHS);
            if (state.angleLabel == null || state.labelTenths != tenths) {
                state.labelTenths = tenths;
                state.angleLabel = String.format(java.util.Locale.ROOT, "%.1f°", tenths / DEGREES_TO_TENTHS);
                state.labelDirty = true;
            }
            if (resources.labelHandles != null) {
                drawCenterHandleWidget(state, resources, graph, objectsOnComponent, center);
                drawButtonWidgets(state, resources, graph, objectsOnComponent, lockPos, resetPos, showReset);
                drawCachedAngleLabel(
                        state,
                        resources,
                        graph,
                        objectsOnComponent,
                        handle.x() + LABEL_OFFSET_X,
                        handle.y() + LABEL_OFFSET_Y);
            } else {
                // Fallback path for synthetic test hosts without GImageEntity.
                marker(handles, drawer, center.x(), center.y(), markerDark, markerLight, true);
                handles.drawerSetOrder.invokeExact(drawer, ORDER_BUTTON);
                angleText(
                        handles,
                        drawer,
                        state.locked ? FALLBACK_LOCKED_GLYPH : FALLBACK_UNLOCKED_GLYPH,
                        resources,
                        lockPos.x(),
                        lockPos.y(),
                        markerLight);
                if (showReset) {
                    angleText(
                            handles, drawer, FALLBACK_RESET_GLYPH, resources, resetPos.x(), resetPos.y(), markerLight);
                }
                handles.drawerSetOrder.invokeExact(drawer, ORDER_LABEL_OUTLINE);
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
                handles.drawerSetOrder.invokeExact(drawer, ORDER_LABEL_FILL);
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
            CounterpartHandles handles = COUNTERPARTS.get(ref.getClass());
            if (!handles.isMeshPointRef(ref)) return false;
            float[] positions = (float[]) handles.getPositions.invokeExact(ref);
            int index = (int) handles.getIndex.invokeExact(ref);
            if (index < 0 || index * 2 + 1 >= positions.length) return false;
            BitSet written = state.writtenBits.computeIfAbsent(positions, ignored -> new BitSet());
            if (written.get(index)) return true;
            Pairing pairs = pairing(state, ref, handles, positions);
            int counterpart = pairs.counterparts[index];
            if (counterpart < 0 || counterpart * 2 + 1 >= positions.length) return false;

            Object transform = handles.getTransform == null ? null : handles.getTransform.invokeExact(ref);
            TransformHandles transformHandles = transform == null ? null : TRANSFORMS.get(transform.getClass());
            float localX = positions[index * 2];
            float localY = positions[index * 2 + 1];
            float otherLocalX = positions[counterpart * 2];
            float otherLocalY = positions[counterpart * 2 + 1];

            float currentX;
            float currentY;
            float otherX;
            float otherY;
            ScratchVectors scratch = transform != null ? scratchVectors(handles) : null;
            if (transform == null) {
                currentX = localX;
                currentY = localY;
                otherX = otherLocalX;
                otherY = otherLocalY;
            } else {
                Object src = prepareSrcVector(handles, scratch, localX, localY);
                Object out = transformHandles.toCanvas.invokeExact(
                        transform, src, scratch == null ? null : scratch.dst, index);
                if (out == null) out = src;
                currentX = (float) handles.vectorX.invokeExact(out);
                currentY = (float) handles.vectorY.invokeExact(out);

                Object otherSrc = prepareSrcVector(handles, scratch, otherLocalX, otherLocalY);
                Object otherOut = transformHandles.toCanvas.invokeExact(
                        transform, otherSrc, scratch == null ? null : scratch.dst, counterpart);
                if (otherOut == null) otherOut = otherSrc;
                otherX = (float) handles.vectorX.invokeExact(otherOut);
                otherY = (float) handles.vectorY.invokeExact(otherOut);
            }

            if (state.movement.driver(positions, index, counterpart, currentX, currentY, otherX, otherY) != index) {
                return true;
            }

            // Mirror the actual weighted displacement in canvas coordinates. Parent transforms
            // can be nonlinear and index-dependent, so blending in canvas space is not equivalent.
            float reqX;
            float reqY;
            float actualX;
            float actualY;
            if (transform == null) {
                reqX = (float) handles.vectorX.invokeExact(target);
                reqY = (float) handles.vectorY.invokeExact(target);
                actualX = localX * (1.0f - weight) + reqX * weight;
                actualY = localY * (1.0f - weight) + reqY * weight;
            } else if (weight == 1.0f) {
                actualX = (float) handles.vectorX.invokeExact(target);
                actualY = (float) handles.vectorY.invokeExact(target);
            } else {
                Object localTarget = transformHandles.toLocal.invokeExact(
                        transform, target, scratch == null ? null : scratch.dst, index);
                if (localTarget == null) localTarget = target;
                reqX = (float) handles.vectorX.invokeExact(localTarget);
                reqY = (float) handles.vectorY.invokeExact(localTarget);
                float blendedX = localX * (1.0f - weight) + reqX * weight;
                float blendedY = localY * (1.0f - weight) + reqY * weight;
                Object blendedSrc = prepareSrcVector(handles, scratch, blendedX, blendedY);
                Object actualOut = transformHandles.toCanvas.invokeExact(
                        transform, blendedSrc, scratch == null ? null : scratch.dst, index);
                if (actualOut == null) actualOut = blendedSrc;
                actualX = (float) handles.vectorX.invokeExact(actualOut);
                actualY = (float) handles.vectorY.invokeExact(actualOut);
            }

            float vx = actualX - currentX;
            float vy = actualY - currentY;
            float normalX = -state.axis.dirY();
            float normalY = state.axis.dirX();
            float signedDist = vx * normalX + vy * normalY;
            float dx = vx - 2.0f * signedDist * normalX;
            float dy = vy - 2.0f * signedDist * normalY;
            float nextX = otherX + dx;
            float nextY = otherY + dy;
            if (!Float.isFinite(nextX) || !Float.isFinite(nextY)) return false;

            if (handles.formSetUpdated != null) {
                float nextLocalX;
                float nextLocalY;
                if (transform == null) {
                    nextLocalX = nextX;
                    nextLocalY = nextY;
                } else {
                    Object nextSrc = prepareSrcVector(handles, scratch, nextX, nextY);
                    Object nextLocal = transformHandles.toLocal.invokeExact(
                            transform, nextSrc, scratch == null ? null : scratch.dst, counterpart);
                    if (nextLocal == null) nextLocal = nextSrc;
                    nextLocalX = (float) handles.vectorX.invokeExact(nextLocal);
                    nextLocalY = (float) handles.vectorY.invokeExact(nextLocal);
                }
                positions[counterpart * 2] = nextLocalX;
                positions[counterpart * 2 + 1] = nextLocalY;
                Object form = handles.getForm.invokeExact(ref);
                if (form != null) handles.formSetUpdated.invokeExact(form);
            } else if (handles.moveRef != null) {
                Object other = counterpart(ref, counterpart, handles);
                Object nextVec = handles.vectorCtor.invokeExact(nextX, nextY);
                APPLYING.set(true);
                try {
                    handles.moveRef.invokeExact(other, nextVec, 1.0f);
                } finally {
                    APPLYING.set(false);
                }
            }
            written.set(index);
            written.set(counterpart);
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
            CounterpartHandles handles = COUNTERPARTS.get(ref.getClass());
            if (!handles.isMeshPointRef(ref)) return;
            Object view = state.view == null ? null : state.view.get();
            if (view == null) return;
            ViewUiHandles uiHandles = VIEW_UI_HANDLES.get(view.getClass());
            Object edit = uiHandles.getCurrentEditMode.invokeExact(view);
            Object model = uiHandles.getCurrentModel.invokeExact(edit);
            Object source = handles.getSource.invokeExact(ref);
            WeightHandles weightHandles = WEIGHT_HANDLES.get(source.getClass());
            Object drawableId = weightHandles.sourceGetId.invokeExact(source);
            Object mesh = uiHandles.getArtMeshForGuid(drawableId.getClass()).invokeExact(model, drawableId);
            if (mesh == null) return;
            WeightMeshHandles meshHandles = WEIGHT_MESH_HANDLES.get(mesh.getClass());
            if (meshHandles.getSource.invokeExact(mesh) != source) return;
            Object form = handles.getForm.invokeExact(ref);
            if (form == null) form = uiHandles.getCalculatedForm.invokeExact(mesh);
            WeightFormHandles formHandles = WEIGHT_FORM_HANDLES.get(form.getClass());
            Object ex;
            if (formHandles.takesTransform) {
                Object transform = handles.getTransform == null ? null : handles.getTransform.invokeExact(ref);
                if (transform == null && meshHandles.getTargetDeformerGuid != null) {
                    WeightModelHandles modelHandles = WEIGHT_MODEL_HANDLES.get(model.getClass());
                    if (modelHandles.getDeformerTransform != null) {
                        Object deformerGuid = meshHandles.getTargetDeformerGuid.invokeExact(mesh);
                        if (deformerGuid != null) {
                            transform = modelHandles.getDeformerTransform.invokeExact(model, deformerGuid);
                        }
                    }
                }
                if (transform == null) return;
                ex = formHandles.getCompatiblePointRefEx.invokeExact(form, ref, transform);
            } else {
                ex = formHandles.getCompatiblePointRefEx.invokeExact(form, ref, model);
            }
            if (ex == null) return;
            CounterpartHandles exHandles = COUNTERPARTS.get(ex.getClass());
            float[] positions = (float[]) exHandles.getPositions.invokeExact(ex);
            Pairing pairs = pairing(state, ex, exHandles, positions);
            int index = (int) exHandles.getIndex.invokeExact(ex);
            int counterpart = pairs.counterparts[index];
            if (counterpart < 0) return;
            Object other = counterpart(ex, counterpart, exHandles);
            SelectorHandles selHandles = SELECTOR_HANDLES.get(selector.getClass());
            Object compatible = selHandles.getCompatible.invokeExact(selector, other);
            if (compatible != null) other = compatible;
            APPLYING.set(true);
            try {
                if (setOnly) selHandles.setWeight.invokeExact(selector, other, weight);
                else selHandles.add.invokeExact(selector, other, weight, reorder);
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
    private enum CallerKind {
        SKIP,
        DRAG_ACTION,
        OTHER
    }

    private static final ClassValue<CallerKind> CALLER_KINDS = new ClassValue<>() {
        @Override
        protected CallerKind computeValue(Class<?> type) {
            String name = type.getName();
            if (name.startsWith(HOST_ADAPTER_PACKAGE) || name.equals(THREAD_CLASS) || name.equals(EX)) {
                return CallerKind.SKIP;
            }
            return name.startsWith(DRAG_ACTION_PACKAGE) ? CallerKind.DRAG_ACTION : CallerKind.OTHER;
        }
    };

    private static StackWalker createStackWalker() {
        try {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        } catch (SecurityException unavailable) {
            return StackWalker.getInstance();
        }
    }

    private static boolean dragCaller() {
        return STACK_WALKER.walk(frames -> frames.map(NativeArtMeshSymmetryBridge::classifyCallerFrame)
                        .filter(kind -> kind != CallerKind.SKIP)
                        .findFirst()
                        .orElse(CallerKind.OTHER))
                == CallerKind.DRAG_ACTION;
    }

    private static CallerKind classifyCallerFrame(StackWalker.StackFrame frame) {
        try {
            return CALLER_KINDS.get(frame.getDeclaringClass());
        } catch (UnsupportedOperationException withoutClassRef) {
            String name = frame.getClassName();
            if (name.startsWith(HOST_ADAPTER_PACKAGE) || name.equals(THREAD_CLASS) || name.equals(EX)) {
                return CallerKind.SKIP;
            }
            return name.startsWith(DRAG_ACTION_PACKAGE) ? CallerKind.DRAG_ACTION : CallerKind.OTHER;
        }
    }

    private static Pairing pairing(State state, Object ref, CounterpartHandles handles, float[] positions)
            throws Throwable {
        Pairing cached = state.pairs.get(positions);
        if (cached != null && cached.counterparts.length * 2 == positions.length) return cached;
        int count = positions.length / 2;
        if (count == 0 || count > MAX_MESH_VERTICES || count * 2 != positions.length)
            throw new IllegalArgumentException("mesh size");
        Object transform = handles.getTransform == null ? null : handles.getTransform.invokeExact(ref);
        float[] canvasPositions;
        if (transform == null) {
            canvasPositions = positions;
        } else {
            TransformHandles transformHandles = TRANSFORMS.get(transform.getClass());
            canvasPositions = new float[count * 2];
            ScratchVectors scratch = scratchVectors(handles);
            for (int i = 0; i < count; i++) {
                Object src = prepareSrcVector(handles, scratch, positions[i * 2], positions[i * 2 + 1]);
                Object out =
                        transformHandles.toCanvas.invokeExact(transform, src, scratch == null ? null : scratch.dst, i);
                if (out == null) out = src;
                canvasPositions[i * 2] = (float) handles.vectorX.invokeExact(out);
                canvasPositions[i * 2 + 1] = (float) handles.vectorY.invokeExact(out);
            }
        }
        Pairing value = new Pairing(pairForView(state.view.get(), state.axis, canvasPositions, count));
        state.pairs.put(positions, value);
        return value;
    }

    static int[] pairForView(Object view, ArtMeshMirrorAxis axis, ArtMeshMirrorAxis.Point[] points)
            throws ReflectiveOperationException {
        float[] xy = new float[points.length * 2];
        for (int i = 0; i < points.length; i++) {
            xy[i * 2] = points[i].x();
            xy[i * 2 + 1] = points[i].y();
        }
        return pairForView(view, axis, xy, points.length);
    }

    private static int[] pairForView(Object view, ArtMeshMirrorAxis axis, float[] xy, int count)
            throws ReflectiveOperationException {
        // Match the native topology editor's move tolerance: 20 component pixels.
        // A mesh-relative tolerance rejects slightly misaligned arbitrary-angle pairs.
        try {
            ViewUiHandles handles = VIEW_UI_HANDLES.get(view.getClass());
            Object camera = handles.getCameraManager.invokeExact(view);
            float tolerance = SCREEN_PAIR_TOLERANCE_PX
                    / projectAxis(handles, camera, axis).scale();
            return axis.pair(xy, count, tolerance);
        } catch (ReflectiveOperationException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException(failure);
        }
    }

    private static Object counterpart(Object ref, int index, CounterpartHandles handles) throws Throwable {
        if (!handles.isMeshPointRef(ref)) throw new NoSuchMethodException(REF);
        Object source = handles.getSource.invokeExact(ref);
        Object form = handles.getForm.invokeExact(ref);
        Object coord = handles.getCoord.invokeExact(ref);
        float[] positions = (float[]) handles.getPositions.invokeExact(ref);
        int step = (int) handles.getStep.invokeExact(ref);
        Object base = handles.baseConstructor(source.getClass(), coord.getClass())
                .invokeExact(source, index, positions, step, coord, form);
        Object transform = handles.getTransform == null ? null : handles.getTransform.invokeExact(ref);
        return handles.exCtor.invokeExact(base, transform, form);
    }

    private static ScratchVectors scratchVectors(CounterpartHandles handles) throws Throwable {
        if (handles.vectorSetX == null || handles.vectorSetY == null) return null;
        ScratchVectors current = SCRATCH_VECTORS.get();
        if (current == null || current.vectorType != handles.vectorType) {
            current = new ScratchVectors(
                    handles.vectorType,
                    handles.vectorCtor.invokeExact(0.0f, 0.0f),
                    handles.vectorCtor.invokeExact(0.0f, 0.0f));
            SCRATCH_VECTORS.set(current);
        }
        return current;
    }

    private static Object prepareSrcVector(CounterpartHandles handles, ScratchVectors scratch, float x, float y)
            throws Throwable {
        if (scratch == null) {
            return handles.vectorCtor.invokeExact(x, y);
        }
        Object vec = scratch.src;
        handles.vectorSetX.invokeExact(vec, x);
        handles.vectorSetY.invokeExact(vec, y);
        return vec;
    }

    private record ScratchVectors(Class<?> vectorType, Object src, Object dst) {}

    /**
     * Per-ref-class handles for zero-reflection point reads, writes, and counterpart construction.
     *
     * <p>The host resolves the point-ref classes, constructors, methods, vector accessors and the
     * local-to-canvas field once per class loader. A drag tick then executes through exact
     * {@link MethodHandle} invocations with zero reflective lookups or method-array allocations.</p>
     */
    private static final ClassValue<CounterpartHandles> COUNTERPARTS = new ClassValue<>() {
        @Override
        protected CounterpartHandles computeValue(Class<?> refType) {
            return CounterpartHandles.resolve(refType);
        }
    };

    private static final class CounterpartHandles {
        /** Marks a ref class that no reviewed host loader backs. */
        private static final CounterpartHandles UNAVAILABLE = new CounterpartHandles();

        private final Class<?> baseType;
        private final Class<?> formType;
        private final Class<?> vectorType;
        final MethodHandle getPositions;
        final MethodHandle getIndex;
        final MethodHandle getStep;
        final MethodHandle getSource;
        final MethodHandle getForm;
        final MethodHandle getCoord;
        final MethodHandle moveRef;
        final MethodHandle getTransform;
        final MethodHandle formSetUpdated;
        final MethodHandle vectorCtor;
        final MethodHandle vectorX;
        final MethodHandle vectorY;
        final MethodHandle vectorSetX;
        final MethodHandle vectorSetY;
        final MethodHandle exCtor;
        private volatile BaseConstructor baseConstructor;

        private CounterpartHandles() {
            this.baseType = null;
            this.formType = null;
            this.vectorType = null;
            this.getPositions = null;
            this.getIndex = null;
            this.getStep = null;
            this.getSource = null;
            this.getForm = null;
            this.getCoord = null;
            this.moveRef = null;
            this.getTransform = null;
            this.formSetUpdated = null;
            this.vectorCtor = null;
            this.vectorX = null;
            this.vectorY = null;
            this.vectorSetX = null;
            this.vectorSetY = null;
            this.exCtor = null;
        }

        private CounterpartHandles(
                Class<?> refType,
                Class<?> baseType,
                Class<?> formType,
                Class<?> vectorType,
                Field transformField,
                Constructor<?> ex)
                throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            this.baseType = baseType;
            this.formType = formType;
            this.vectorType = vectorType;
            this.getPositions = ViewUiHandles.unreflect(lookup, refType, "h", 0)
                    .asType(MethodType.methodType(float[].class, Object.class));
            this.getIndex = ViewUiHandles.unreflect(lookup, refType, "a", 0).asType(INT_GETTER_TYPE);
            this.getStep = ViewUiHandles.unreflect(lookup, refType, "i", 0).asType(INT_GETTER_TYPE);
            this.getSource = ViewUiHandles.unreflect(lookup, refType, "d", 0).asType(OBJECT_GETTER_TYPE);
            this.getForm = ViewUiHandles.unreflect(lookup, refType, "g", 0).asType(OBJECT_GETTER_TYPE);
            this.getCoord = ViewUiHandles.unreflect(lookup, refType, "c", 0).asType(OBJECT_GETTER_TYPE);
            this.moveRef = tryUnreflect(
                    lookup,
                    refType,
                    "a",
                    MethodType.methodType(void.class, Object.class, Object.class, float.class),
                    vectorType,
                    float.class);
            this.getTransform = transformField == null
                    ? null
                    : lookup.unreflectGetter(transformField).asType(OBJECT_GETTER_TYPE);
            this.formSetUpdated = tryUnreflect(lookup, formType, "setUpdated", VOID_OBJECT_TYPE);
            Constructor<?> vCtor = vectorType.getConstructor(float.class, float.class);
            ReflectAccess.open(vCtor);
            this.vectorCtor = lookup.unreflectConstructor(vCtor)
                    .asType(MethodType.methodType(Object.class, float.class, float.class));
            this.vectorX =
                    ViewUiHandles.unreflect(lookup, vectorType, "getX", 0).asType(FLOAT_GETTER_TYPE);
            this.vectorY =
                    ViewUiHandles.unreflect(lookup, vectorType, "getY", 0).asType(FLOAT_GETTER_TYPE);
            this.vectorSetX = tryUnreflect(
                    lookup,
                    vectorType,
                    "setX",
                    MethodType.methodType(void.class, Object.class, float.class),
                    float.class);
            this.vectorSetY = tryUnreflect(
                    lookup,
                    vectorType,
                    "setY",
                    MethodType.methodType(void.class, Object.class, float.class),
                    float.class);
            ReflectAccess.open(ex);
            this.exCtor = lookup.unreflectConstructor(ex)
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
        }

        static CounterpartHandles resolve(Class<?> refType) {
            ClassLoader loader = refType.getClassLoader();
            try {
                Class<?> base = Class.forName(REF, false, loader);
                if (!base.isAssignableFrom(refType)) return UNAVAILABLE;
                Class<?> form = Class.forName(ART_MESH_FORM_CLASS, false, loader);
                Class<?> transformType = Class.forName(TRANSFORM_CLASS, false, loader);
                Class<?> vectorType = Class.forName(GVECTOR2_CLASS, false, loader);
                Constructor<?> ex = Class.forName(EX, false, loader).getConstructor(base, transformType, form);
                return new CounterpartHandles(refType, base, form, vectorType, transformField(refType), ex);
            } catch (ReflectiveOperationException unavailable) {
                return UNAVAILABLE;
            }
        }

        private static MethodHandle tryUnreflect(
                MethodHandles.Lookup lookup, Class<?> owner, String name, MethodType adapted, Class<?>... params) {
            try {
                return ViewUiHandles.unreflectExact(lookup, owner, name, params).asType(adapted);
            } catch (ReflectiveOperationException unavailable) {
                return null;
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

        MethodHandle baseConstructor(Class<?> sourceType, Class<?> coordinateType) throws NoSuchMethodException {
            BaseConstructor cached = baseConstructor;
            if (cached != null && cached.sourceType == sourceType && cached.coordinateType == coordinateType) {
                return cached.handle;
            }
            if (baseType == null) throw new NoSuchMethodException(REF);
            Constructor<?> resolved =
                    baseType.getConstructor(sourceType, int.class, float[].class, int.class, coordinateType, formType);
            ReflectAccess.open(resolved);
            MethodHandle handle;
            try {
                handle = MethodHandles.lookup()
                        .unreflectConstructor(resolved)
                        .asType(MethodType.methodType(
                                Object.class,
                                Object.class,
                                int.class,
                                float[].class,
                                int.class,
                                Object.class,
                                Object.class));
            } catch (IllegalAccessException failure) {
                throw new NoSuchMethodException(failure.getMessage());
            }
            baseConstructor = new BaseConstructor(sourceType, coordinateType, handle);
            return handle;
        }

        private record BaseConstructor(Class<?> sourceType, Class<?> coordinateType, MethodHandle handle) {}
    }

    private static final ClassValue<TransformHandles> TRANSFORMS = new ClassValue<>() {
        @Override
        protected TransformHandles computeValue(Class<?> transformType) {
            try {
                return new TransformHandles(transformType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class TransformHandles {
        private static final MethodType TRANSFORM_METHOD_TYPE =
                MethodType.methodType(Object.class, Object.class, Object.class, Object.class, int.class);
        final MethodHandle toLocal;
        final MethodHandle toCanvas;

        TransformHandles(Class<?> transformType) throws ReflectiveOperationException {
            ClassLoader loader = transformType.getClassLoader();
            Class<?> owner = transformType;
            try {
                Class<?> iface = Class.forName(TRANSFORM_CLASS, false, loader);
                if (iface.isAssignableFrom(transformType)) owner = iface;
            } catch (ClassNotFoundException ignored) {
                // Fall back to the concrete runtime class.
            }
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            this.toLocal = resolveTransformMethod(lookup, owner, "transformCanvasToLocal");
            this.toCanvas = resolveTransformMethod(lookup, owner, "transformLocalToCanvas");
        }

        private static MethodHandle resolveTransformMethod(MethodHandles.Lookup lookup, Class<?> owner, String name)
                throws ReflectiveOperationException {
            for (Method candidate : MethodHandleCache.overloads(owner, name, 3)) {
                Class<?>[] params = candidate.getParameterTypes();
                if (params[2] == int.class) {
                    ReflectAccess.open(candidate);
                    return lookup.unreflect(candidate).asType(TRANSFORM_METHOD_TYPE);
                }
            }
            throw new NoSuchMethodException(owner.getName() + "#" + name + "/3");
        }
    }

    private static final ClassValue<Boolean> ART_MESH_SOURCE_TYPES = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return type.getName().equals(ARTMESH_SOURCE_CLASS);
        }
    };

    private static final ClassValue<MethodHandle> FORM_POSITIONS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> formType) {
            try {
                return ViewUiHandles.unreflect(MethodHandles.lookup(), formType, "getPositions", 0)
                        .asType(MethodType.methodType(float[].class, Object.class));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final ClassValue<WeightHandles> WEIGHT_HANDLES = new ClassValue<>() {
        @Override
        protected WeightHandles computeValue(Class<?> sourceType) {
            try {
                return new WeightHandles(sourceType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class WeightHandles {
        final MethodHandle sourceGetId;

        WeightHandles(Class<?> sourceType) throws ReflectiveOperationException {
            sourceGetId = ViewUiHandles.unreflect(MethodHandles.lookup(), sourceType, "getId", 0)
                    .asType(OBJECT_GETTER_TYPE);
        }
    }

    private static final ClassValue<WeightMeshHandles> WEIGHT_MESH_HANDLES = new ClassValue<>() {
        @Override
        protected WeightMeshHandles computeValue(Class<?> meshType) {
            try {
                return new WeightMeshHandles(meshType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class WeightMeshHandles {
        final MethodHandle getSource;
        final MethodHandle getTargetDeformerGuid;

        WeightMeshHandles(Class<?> meshType) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getSource =
                    ViewUiHandles.unreflect(lookup, meshType, "getSource", 0).asType(OBJECT_GETTER_TYPE);
            getTargetDeformerGuid =
                    CounterpartHandles.tryUnreflect(lookup, meshType, "getTargetDeformerGuid", OBJECT_GETTER_TYPE);
        }
    }

    private static final ClassValue<WeightModelHandles> WEIGHT_MODEL_HANDLES = new ClassValue<>() {
        @Override
        protected WeightModelHandles computeValue(Class<?> modelType) {
            return new WeightModelHandles(modelType);
        }
    };

    private static final class WeightModelHandles {
        final MethodHandle getDeformerTransform;

        WeightModelHandles(Class<?> modelType) {
            MethodHandle resolved = null;
            try {
                resolved = ViewUiHandles.unreflect(MethodHandles.lookup(), modelType, "getDeformerTransform", 1)
                        .asType(OBJECT_ARG_OBJECT_TYPE);
            } catch (ReflectiveOperationException ignored) {
                // Absent on synthetic test models without deformer transforms.
            }
            getDeformerTransform = resolved;
        }
    }

    private static final ClassValue<WeightFormHandles> WEIGHT_FORM_HANDLES = new ClassValue<>() {
        @Override
        protected WeightFormHandles computeValue(Class<?> formType) {
            try {
                return new WeightFormHandles(formType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class WeightFormHandles {
        final MethodHandle getCompatiblePointRefEx;
        final boolean takesTransform;

        WeightFormHandles(Class<?> formType) throws ReflectiveOperationException {
            MethodHandle raw = ViewUiHandles.unreflect(MethodHandles.lookup(), formType, "getCompatiblePointRefEx", 2);
            takesTransform = raw.type().parameterType(2).getName().equals(TRANSFORM_CLASS);
            getCompatiblePointRefEx =
                    raw.asType(MethodType.methodType(Object.class, Object.class, Object.class, Object.class));
        }
    }

    private static final ClassValue<SelectorHandles> SELECTOR_HANDLES = new ClassValue<>() {
        @Override
        protected SelectorHandles computeValue(Class<?> selectorType) {
            try {
                return new SelectorHandles(selectorType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class SelectorHandles {
        final MethodHandle getCompatible;
        final MethodHandle setWeight;
        final MethodHandle add;

        SelectorHandles(Class<?> selectorType) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getCompatible = ViewUiHandles.unreflect(lookup, selectorType, "getCompatible", 1)
                    .asType(OBJECT_ARG_OBJECT_TYPE);
            setWeight = ViewUiHandles.unreflect(lookup, selectorType, "setWeight", 2)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, float.class));
            add = ViewUiHandles.unreflect(lookup, selectorType, "add", 3)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, float.class, boolean.class));
        }
    }

    private record ProjectedAxis(ArtMeshMirrorAxis.Point center, ArtMeshMirrorAxis.Point direction, float scale) {}

    private static ProjectedAxis projectAxis(ViewUiHandles handles, Object camera, ArtMeshMirrorAxis axis)
            throws Throwable {
        var pivot = axis.pivot();
        var center = project(handles, camera, pivot.x(), pivot.y());
        // A one-unit probe can round to the same screen pixel when zoomed out, or
        // to the same model float at large coordinates. Use a well-separated probe.
        float step =
                Math.max(PROBE_MIN_STEP, Math.max(Math.ulp(pivot.x()), Math.ulp(pivot.y())) * PROBE_ULP_MULTIPLIER);
        var end = project(handles, camera, pivot.x() + axis.dirX() * step, pivot.y() + axis.dirY() * step);
        var delta = end.minus(center);
        float length = (float) Math.hypot(delta.x(), delta.y());
        if (!(length > 0)) throw new IllegalStateException("degenerate camera projection");
        return new ProjectedAxis(
                center, new ArtMeshMirrorAxis.Point(delta.x() / length, delta.y() / length), length / step);
    }

    private static ArtMeshMirrorAxis.Point rotationHandle(Object camera, Object view, ArtMeshMirrorAxis axis)
            throws ReflectiveOperationException {
        try {
            return rotationHandle(projectAxis(VIEW_UI_HANDLES.get(view.getClass()), camera, axis), axis);
        } catch (ReflectiveOperationException | RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException(failure);
        }
    }

    private static ArtMeshMirrorAxis.Point rotationHandle(ProjectedAxis projection, ArtMeshMirrorAxis axis) {
        float arm = Math.max(ROTATION_ARM_MIN_PX, axis.rotationRadius() * projection.scale());
        return new ArtMeshMirrorAxis.Point(
                projection.center().x() + projection.direction().x() * arm,
                projection.center().y() + projection.direction().y() * arm);
    }

    private static boolean shouldShowResetButton(ArtMeshMirrorAxis axis) {
        return axis != null && !axis.isAtInitialState();
    }

    private static ArtMeshMirrorAxis.Point lockButtonPosition(ProjectedAxis projection) {
        return axisControlOffset(projection, -LOCK_BUTTON_OFFSET_PX);
    }

    private static ArtMeshMirrorAxis.Point resetButtonPosition(ProjectedAxis projection) {
        return axisControlOffset(projection, -RESET_BUTTON_OFFSET_PX);
    }

    private static ArtMeshMirrorAxis.Point axisControlOffset(ProjectedAxis projection, float offsetPx) {
        return new ArtMeshMirrorAxis.Point(
                projection.center().x() + projection.direction().x() * offsetPx,
                projection.center().y() + projection.direction().y() * offsetPx);
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
        final Object dark, light, mutedDark, mutedLight, font, layer;
        final Font awtFont;
        final LabelHandles labelHandles;

        DrawingResources(ClassLoader loader) throws ReflectiveOperationException {
            var color = Class.forName(CCOLOR_CLASS, false, loader).getConstructor(int.class, int.class, int.class);
            dark = color.newInstance(DARK_R, DARK_G, DARK_B);
            light = color.newInstance(LIGHT_RGB, LIGHT_RGB, LIGHT_RGB);
            mutedDark = color.newInstance(MUTED_DARK_R, MUTED_DARK_G, MUTED_DARK_B);
            mutedLight = color.newInstance(MUTED_LIGHT_R, MUTED_LIGHT_G, MUTED_LIGHT_B);
            font = Class.forName(CFONT_CLASS, false, loader)
                    .getConstructor(int.class)
                    .newInstance(LABEL_FONT_CTOR_SIZE);
            awtFont = resolveAwtFont(font);
            layer = Class.forName(LAYER_ENUM_CLASS, false, loader).getField("a").get(null);
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

    private static final class IconButtonWidget {
        final Object texture;
        final Object entity;

        IconButtonWidget(String entityName, BufferedImage icon, LabelHandles handles, Object layer, int orderInLayer)
                throws Throwable {
            Object resource = handles.resourceCtor.invokeExact(icon, true);
            texture = handles.textureCtor.invokeExact(resource, (Object) null, true);
            Object filterMode = handles.getFilterMode.invokeExact(texture);
            handles.setMinFilter.invokeExact(filterMode, handles.minFilterLinear);
            handles.setMagFilter.invokeExact(filterMode, handles.magFilterLinear);
            handles.setPreload.invokeExact(texture, true);
            Object bounds = handles.rectCtor.invokeExact(0f, 0f, (float) icon.getWidth(), (float) icon.getHeight());
            entity = handles.imageEntityCtor.invokeExact(entityName, texture, false, bounds);
            Object renderer = handles.getMeshRenderer.invokeExact(entity);
            handles.setSortingLayer.invokeExact(renderer, layer);
            handles.setOrderInLayer.invokeExact(renderer, orderInLayer);
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
            Object resource = handles.resourceCtor.invokeExact(image, true);
            texture = handles.textureCtor.invokeExact(resource, (Object) null, true);
            Object filterMode = handles.getFilterMode.invokeExact(texture);
            handles.setMinFilter.invokeExact(filterMode, handles.minFilterLinear);
            handles.setMagFilter.invokeExact(filterMode, handles.magFilterLinear);
            handles.setPreload.invokeExact(texture, true);
            Object initialBounds =
                    handles.rectCtor.invokeExact(0f, 0f, (float) LABEL_IMAGE_WIDTH, (float) LABEL_IMAGE_HEIGHT);
            entity = handles.imageEntityCtor.invokeExact("ArtMeshMirrorAngleLabel", texture, false, initialBounds);
            Object renderer = handles.getMeshRenderer.invokeExact(entity);
            handles.setSortingLayer.invokeExact(renderer, layer);
            handles.setOrderInLayer.invokeExact(renderer, ORDER_LABEL_FILL);
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
            handles.reinitTexture.invokeExact(texture);
        }
    }

    private static void drawCenterHandleWidget(
            State state,
            DrawingResources resources,
            Object graph,
            Object objectsOnComponent,
            ArtMeshMirrorAxis.Point center)
            throws Throwable {
        LabelHandles handles = resources.labelHandles;
        if (state.locked) {
            if (state.centerHandleLockedWidget == null) {
                state.centerHandleLockedWidget = new IconButtonWidget(
                        CENTER_HANDLE_LOCKED_ENTITY_NAME,
                        ArtMeshMirrorAxisIcons.centerHandleIcon(true),
                        handles,
                        resources.layer,
                        ORDER_CENTER_MARKER);
            }
            disableButtonWidget(state.centerHandleWidget, handles);
            showEntityAt(
                    state.centerHandleLockedWidget.entity, handles, graph, objectsOnComponent, center.x(), center.y());
        } else {
            if (state.centerHandleWidget == null) {
                state.centerHandleWidget = new IconButtonWidget(
                        CENTER_HANDLE_ENTITY_NAME,
                        ArtMeshMirrorAxisIcons.centerHandleIcon(false),
                        handles,
                        resources.layer,
                        ORDER_CENTER_MARKER);
            }
            disableButtonWidget(state.centerHandleLockedWidget, handles);
            showEntityAt(state.centerHandleWidget.entity, handles, graph, objectsOnComponent, center.x(), center.y());
        }
    }

    private static void drawButtonWidgets(
            State state,
            DrawingResources resources,
            Object graph,
            Object objectsOnComponent,
            ArtMeshMirrorAxis.Point lockPos,
            ArtMeshMirrorAxis.Point resetPos,
            boolean showReset)
            throws Throwable {
        LabelHandles handles = resources.labelHandles;
        if (state.locked) {
            if (state.lockedButtonWidget == null) {
                state.lockedButtonWidget = new IconButtonWidget(
                        LOCK_BUTTON_ENTITY_NAME,
                        ArtMeshMirrorAxisIcons.lockedIcon(),
                        handles,
                        resources.layer,
                        ORDER_BUTTON);
            }
            disableButtonWidget(state.unlockedButtonWidget, handles);
            showEntityAt(state.lockedButtonWidget.entity, handles, graph, objectsOnComponent, lockPos.x(), lockPos.y());
        } else {
            if (state.unlockedButtonWidget == null) {
                state.unlockedButtonWidget = new IconButtonWidget(
                        UNLOCK_BUTTON_ENTITY_NAME,
                        ArtMeshMirrorAxisIcons.unlockedIcon(),
                        handles,
                        resources.layer,
                        ORDER_BUTTON);
            }
            disableButtonWidget(state.lockedButtonWidget, handles);
            showEntityAt(
                    state.unlockedButtonWidget.entity, handles, graph, objectsOnComponent, lockPos.x(), lockPos.y());
        }
        if (!showReset) {
            disableButtonWidget(state.resetEnabledButtonWidget, handles);
            disableButtonWidget(state.resetDisabledButtonWidget, handles);
            return;
        }
        if (state.locked) {
            if (state.resetDisabledButtonWidget == null) {
                state.resetDisabledButtonWidget = new IconButtonWidget(
                        RESET_DISABLED_BUTTON_ENTITY_NAME,
                        ArtMeshMirrorAxisIcons.resetIcon(false),
                        handles,
                        resources.layer,
                        ORDER_BUTTON);
            }
            disableButtonWidget(state.resetEnabledButtonWidget, handles);
            showEntityAt(
                    state.resetDisabledButtonWidget.entity,
                    handles,
                    graph,
                    objectsOnComponent,
                    resetPos.x(),
                    resetPos.y());
        } else {
            if (state.resetEnabledButtonWidget == null) {
                state.resetEnabledButtonWidget = new IconButtonWidget(
                        RESET_BUTTON_ENTITY_NAME,
                        ArtMeshMirrorAxisIcons.resetIcon(true),
                        handles,
                        resources.layer,
                        ORDER_BUTTON);
            }
            disableButtonWidget(state.resetDisabledButtonWidget, handles);
            showEntityAt(
                    state.resetEnabledButtonWidget.entity,
                    handles,
                    graph,
                    objectsOnComponent,
                    resetPos.x(),
                    resetPos.y());
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
        showEntityAt(
                widget.entity,
                handles,
                graph,
                objectsOnComponent,
                x + LABEL_IMAGE_WIDTH * 0.5f,
                y + LABEL_IMAGE_HEIGHT * 0.5f);
    }

    private static void showEntityAt(
            Object entity, LabelHandles handles, Object graph, Object objectsOnComponent, float centerX, float centerY)
            throws Throwable {
        Object transform = handles.getTransform.invokeExact(entity);
        Object pos = handles.getPosition.invokeExact(transform);
        handles.setVector3.invokeExact(pos, centerX, centerY, 0f);
        if (handles.getParentEntity.invokeExact(entity) != objectsOnComponent) {
            Object children = handles.getChildren.invokeExact(objectsOnComponent);
            handles.addEntity.invokeExact(children, entity, 0);
        }
        handles.setVolatile.invokeExact(graph, entity);
    }

    private static void hideOverlayWidgets(State state) {
        LabelHandles handles = findLabelHandles(state);
        if (handles == null) return;
        if (state.angleWidget != null) {
            disableEntity(state.angleWidget.entity, handles);
        }
        disableButtonWidget(state.centerHandleWidget, handles);
        disableButtonWidget(state.centerHandleLockedWidget, handles);
        disableButtonWidget(state.unlockedButtonWidget, handles);
        disableButtonWidget(state.lockedButtonWidget, handles);
        disableButtonWidget(state.resetEnabledButtonWidget, handles);
        disableButtonWidget(state.resetDisabledButtonWidget, handles);
    }

    private static LabelHandles findLabelHandles(State state) {
        Object sampleEntity = state.angleWidget != null
                ? state.angleWidget.entity
                : state.centerHandleWidget != null
                        ? state.centerHandleWidget.entity
                        : state.centerHandleLockedWidget != null
                                ? state.centerHandleLockedWidget.entity
                                : state.unlockedButtonWidget != null
                                        ? state.unlockedButtonWidget.entity
                                        : state.lockedButtonWidget != null
                                                ? state.lockedButtonWidget.entity
                                                : state.resetEnabledButtonWidget != null
                                                        ? state.resetEnabledButtonWidget.entity
                                                        : state.resetDisabledButtonWidget != null
                                                                ? state.resetDisabledButtonWidget.entity
                                                                : null;
        return sampleEntity == null ? null : DRAWING.get(sampleEntity.getClass()).labelHandles;
    }

    private static void disableButtonWidget(IconButtonWidget widget, LabelHandles handles) {
        if (widget != null) {
            disableEntity(widget.entity, handles);
        }
    }

    private static void disableEntity(Object entity, LabelHandles handles) {
        try {
            handles.setEnabled.invokeExact(entity, false);
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
        handles.drawerText.invokeExact(drawer, text, resources.font, x, y, 0f, LABEL_FONT_SIZE_PT, color);
    }

    private static void marker(
            ViewUiHandles handles,
            Object drawer,
            float x,
            float y,
            Object darkColor,
            Object lightColor,
            boolean diamond)
            throws Throwable {
        float r = diamond ? MARKER_DIAMOND_RADIUS_PX : MARKER_SQUARE_RADIUS_PX;
        float x0 = diamond ? x : x - r;
        float y0 = y - r;
        float x1 = x + r;
        float y1 = diamond ? y : y - r;
        float x2 = diamond ? x : x + r;
        float y2 = y + r;
        float x3 = x - r;
        float y3 = diamond ? y : y + r;
        for (int layer = 0; layer < 2; layer++) {
            handles.drawerSetOrder.invokeExact(drawer, ORDER_MARKER_BASE + layer);
            float width = layer == 0 ? AXIS_LINE_DARK_WIDTH : AXIS_LINE_LIGHT_WIDTH;
            Object color = layer == 0 ? darkColor : lightColor;
            line(handles, drawer, x0, y0, x1, y1, color, width);
            line(handles, drawer, x1, y1, x2, y2, color, width);
            line(handles, drawer, x2, y2, x3, y3, color, width);
            line(handles, drawer, x3, y3, x0, y0, color, width);
        }
    }

    private static ArtMeshMirrorAxis.Point project(ViewUiHandles handles, Object camera, ArtMeshMirrorAxis.Point point)
            throws Throwable {
        return project(handles, camera, point.x(), point.y());
    }

    private static ArtMeshMirrorAxis.Point project(ViewUiHandles handles, Object camera, float x, float y)
            throws Throwable {
        return read(handles, handles.documentToComponent.invokeExact(camera, handles.vectorCtor.invokeExact(x, y)));
    }

    private static void line(
            ViewUiHandles handles, Object drawer, float x1, float y1, float x2, float y2, Object color, float width)
            throws Throwable {
        handles.drawerLine.invokeExact(
                drawer,
                handles.vectorCtor.invokeExact(x1, y1),
                handles.vectorCtor.invokeExact(x2, y2),
                color,
                width,
                0f);
    }

    private static double distance(ArtMeshMirrorAxis.Point a, ArtMeshMirrorAxis.Point b) {
        return Math.hypot(a.x() - b.x(), a.y() - b.y());
    }

    private static ArtMeshMirrorAxis.Point read(ViewUiHandles handles, Object vector) throws Throwable {
        return new ArtMeshMirrorAxis.Point(
                (float) handles.vectorX.invokeExact(vector), (float) handles.vectorY.invokeExact(vector));
    }

    private static final ClassValue<StateHandles> STATE_HANDLES = new ClassValue<>() {
        @Override
        protected StateHandles computeValue(Class<?> viewType) {
            if (!viewType.getName().equals(MODELING_VIEW_CLASS)) {
                return StateHandles.UNAVAILABLE;
            }
            try {
                return new StateHandles(viewType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final ClassValue<Optional<MethodHandle>> MODEL_SOURCE_GUID = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> sourceType) {
            return Optional.ofNullable(tryUnreflectObjectGetter(sourceType, "getGuid"));
        }
    };

    private static final ClassValue<Optional<MethodHandle>> GUID_UUID_STRING = new ClassValue<>() {
        @Override
        protected Optional<MethodHandle> computeValue(Class<?> guidType) {
            return Optional.ofNullable(tryUnreflectObjectGetter(guidType, "getUuidString"));
        }
    };

    private static MethodHandle tryUnreflectObjectGetter(Class<?> type, String name) {
        try {
            return ViewUiHandles.unreflect(MethodHandles.lookup(), type, name, 0)
                    .asType(OBJECT_GETTER_TYPE);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String resolveModelGuid(Object source) {
        if (source == null) {
            return null;
        }
        MethodHandle getter = MODEL_SOURCE_GUID.get(source.getClass()).orElse(null);
        if (getter == null) {
            return null;
        }
        try {
            return extractGuidString(getter.invokeExact(source));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static String extractGuidString(Object rawGuid) {
        if (rawGuid == null) {
            return null;
        }
        String text;
        if (rawGuid instanceof String value) {
            text = value;
        } else {
            MethodHandle uuidGetter = GUID_UUID_STRING.get(rawGuid.getClass()).orElse(null);
            if (uuidGetter != null) {
                try {
                    Object uuid = uuidGetter.invokeExact(rawGuid);
                    text = uuid == null ? null : String.valueOf(uuid);
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    text = null;
                }
            } else {
                text = String.valueOf(rawGuid);
            }
        }
        if (text == null) {
            return null;
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static final ClassValue<MethodHandle> MODEL_SOURCES = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(Class<?> modelType) {
            try {
                return ViewUiHandles.unreflect(MethodHandles.lookup(), modelType, "getSource", 0)
                        .asType(OBJECT_GETTER_TYPE);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class StateHandles {
        private static final StateHandles UNAVAILABLE = new StateHandles();

        final boolean isModelingView;
        final Class<?> editType;
        final MethodHandle getCurrentEditMode;
        final MethodHandle getCurrentModel;

        private StateHandles() {
            this.isModelingView = false;
            this.editType = null;
            this.getCurrentEditMode = null;
            this.getCurrentModel = null;
        }

        StateHandles(Class<?> viewType) throws ReflectiveOperationException {
            this.isModelingView = true;
            ClassLoader loader = viewType.getClassLoader();
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            getCurrentEditMode = ViewUiHandles.unreflect(lookup, viewType, "getCurrentEditMode", 0)
                    .asType(OBJECT_GETTER_TYPE);
            editType = Class.forName(MAIN_EDIT_MODE_CLASS, false, loader);
            getCurrentModel = ViewUiHandles.unreflect(lookup, editType, "getCurrentModel", 0)
                    .asType(OBJECT_GETTER_TYPE);
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
                return ViewUiHandles.unreflect(lookup, type, name, 0).asType(OBJECT_GETTER_TYPE);
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
                return ViewUiHandles.unreflect(lookup, type, name, 0).asType(OBJECT_GETTER_TYPE);
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
        final MethodHandle getArtMeshById;
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
            getCurrentEditMode =
                    unreflect(lookup, viewType, "getCurrentEditMode", 0).asType(OBJECT_GETTER_TYPE);
            MethodHandle rawCamera = unreflect(lookup, viewType, "getCameraManager", 0);
            Class<?> cameraType = rawCamera.type().returnType();
            getCameraManager = rawCamera.asType(OBJECT_GETTER_TYPE);

            MethodHandle rawRenderSystem = unreflect(lookup, viewType, "getRenderSystem", 0);
            Class<?> renderSystemType = rawRenderSystem.type().returnType();
            getRenderSystem = rawRenderSystem.asType(OBJECT_GETTER_TYPE);

            MethodHandle rawSceneGraph = unreflect(lookup, viewType, "getSceneGraph", 0);
            Class<?> graphType = rawSceneGraph.type().returnType();
            getSceneGraph = rawSceneGraph.asType(OBJECT_GETTER_TYPE);

            repaintCanvas = unreflect(lookup, viewType, "repaintCanvas", 0).asType(VOID_OBJECT_TYPE);

            Class<?> editType = Class.forName(MAIN_EDIT_MODE_CLASS, false, loader);
            MethodHandle rawModel = unreflect(lookup, editType, "getCurrentModel", 0);
            modelType = rawModel.type().returnType();
            getCurrentModel = rawModel.asType(OBJECT_GETTER_TYPE);
            MethodHandle rawSelector = unreflect(lookup, editType, "getSelector", 0);
            Class<?> selectorType = rawSelector.type().returnType();
            getSelector = rawSelector.asType(OBJECT_GETTER_TYPE);
            MethodHandle rawByGuid = findArtMeshLookup(lookup, modelType, "Guid");
            MethodHandle rawById = findArtMeshLookup(lookup, modelType, "Id");
            MethodHandle rawByString = findArtMeshLookup(lookup, modelType, "String");
            MethodHandle sampleArtMesh = rawByGuid != null ? rawByGuid : rawById != null ? rawById : rawByString;
            if (sampleArtMesh == null) {
                throw new NoSuchMethodException(modelType.getName() + "#getArtMesh/1");
            }
            getCalculatedForm = unreflect(lookup, sampleArtMesh.type().returnType(), "getCalculatedForm", 0)
                    .asType(OBJECT_GETTER_TYPE);
            getArtMeshByGuid = rawByGuid == null ? null : rawByGuid.asType(OBJECT_ARG_OBJECT_TYPE);
            getArtMeshById = rawById == null ? null : rawById.asType(OBJECT_ARG_OBJECT_TYPE);
            getArtMeshByString = rawByString == null ? null : rawByString.asType(OBJECT_ARG_OBJECT_TYPE);

            getSelected = unreflect(lookup, selectorType, "getSelected", 0).asType(OBJECT_GETTER_TYPE);

            isSafeEditOnPlaneXY =
                    unreflect(lookup, cameraType, "isSafeEditOnPlaneXY", 0).asType(BOOLEAN_GETTER_TYPE);
            Class<?> cpointType = Class.forName(CPOINT_CLASS, false, loader);
            Class<?> vectorType = Class.forName(GVECTOR2_CLASS, false, loader);
            componentToDocument = unreflectExact(lookup, cameraType, "componentToDocument", cpointType)
                    .asType(OBJECT_ARG_OBJECT_TYPE);
            documentToComponent = unreflectExact(lookup, cameraType, "documentToComponent", vectorType)
                    .asType(OBJECT_ARG_OBJECT_TYPE);
            MethodHandle rawComponentRect = unreflect(lookup, cameraType, "getComponentRect", 0);
            Class<?> rectType = rawComponentRect.type().returnType();
            getComponentRect = rawComponentRect.asType(OBJECT_GETTER_TYPE);
            rectX = unreflect(lookup, rectType, "getX", 0).asType(FLOAT_GETTER_TYPE);
            rectY = unreflect(lookup, rectType, "getY", 0).asType(FLOAT_GETTER_TYPE);
            rectWidth = unreflect(lookup, rectType, "getWidth", 0).asType(FLOAT_GETTER_TYPE);
            rectHeight = unreflect(lookup, rectType, "getHeight", 0).asType(FLOAT_GETTER_TYPE);

            Constructor<?> vCtor = vectorType.getConstructor(float.class, float.class);
            ReflectAccess.open(vCtor);
            vectorCtor = lookup.unreflectConstructor(vCtor)
                    .asType(MethodType.methodType(Object.class, float.class, float.class));
            vectorX = unreflect(lookup, vectorType, "getX", 0).asType(FLOAT_GETTER_TYPE);
            vectorY = unreflect(lookup, vectorType, "getY", 0).asType(FLOAT_GETTER_TYPE);

            MethodHandle rawDrawerOf = unreflect(lookup, renderSystemType, "b", 0);
            drawerOf = rawDrawerOf.asType(OBJECT_GETTER_TYPE);
            getObjectsOnComponent =
                    unreflect(lookup, graphType, "getObjectsOnComponent", 0).asType(OBJECT_GETTER_TYPE);

            Class<?> drawerType = rawDrawerOf.type().returnType();
            drawerSetEntity = findDrawerSetter(lookup, drawerType, Object.class)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            drawerSetLayer = findDrawerLayerSetter(lookup, drawerType, loader)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            drawerSetOrder = unreflectExact(lookup, drawerType, "a", int.class)
                    .asType(MethodType.methodType(void.class, Object.class, int.class));
            drawerLine = findDrawerLine(lookup, drawerType, vectorType, loader)
                    .asType(MethodType.methodType(
                            void.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            Object.class,
                            float.class,
                            float.class));
            drawerText = findDrawerText(lookup, drawerType, loader)
                    .asType(MethodType.methodType(
                            void.class,
                            Object.class,
                            String.class,
                            Object.class,
                            float.class,
                            float.class,
                            float.class,
                            float.class,
                            Object.class));
        }

        MethodHandle getArtMeshForGuid(Class<?> idType) throws NoSuchMethodException {
            if (idType == String.class && getArtMeshByString != null) {
                return getArtMeshByString;
            }
            if (idType.getName().endsWith("Guid") && getArtMeshByGuid != null) {
                return getArtMeshByGuid;
            }
            if (idType.getName().endsWith("Id") && getArtMeshById != null) {
                return getArtMeshById;
            }
            if (getArtMeshByGuid != null) {
                return getArtMeshByGuid;
            }
            if (getArtMeshById != null) {
                return getArtMeshById;
            }
            if (getArtMeshByString != null) {
                return getArtMeshByString;
            }
            throw new NoSuchMethodException(modelType.getName() + "#getArtMesh(" + idType.getName() + ")");
        }

        private static MethodHandle findArtMeshLookup(MethodHandles.Lookup lookup, Class<?> modelType, String suffix)
                throws ReflectiveOperationException {
            for (Method method : MethodHandleCache.overloads(modelType, "getArtMesh", 1)) {
                Class<?> param = method.getParameterTypes()[0];
                if ("String".equals(suffix) && param == String.class) {
                    ReflectAccess.open(method);
                    return lookup.unreflect(method);
                }
                if (!"String".equals(suffix)
                        && param != String.class
                        && param.getName().endsWith(suffix)) {
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
                Class<?> layerType = Class.forName(LAYER_BASE_CLASS, false, loader);
                return unreflectExact(lookup, drawerType, "a", layerType);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                return findDrawerSetter(lookup, drawerType, Object.class);
            }
        }

        private static MethodHandle findDrawerLine(
                MethodHandles.Lookup lookup, Class<?> drawerType, Class<?> vectorType, ClassLoader loader)
                throws ReflectiveOperationException {
            Class<?> colorType = Class.forName(CCOLOR_CLASS, false, loader);
            return unreflectExact(lookup, drawerType, "a", vectorType, vectorType, colorType, float.class, float.class);
        }

        private static MethodHandle findDrawerText(MethodHandles.Lookup lookup, Class<?> drawerType, ClassLoader loader)
                throws ReflectiveOperationException {
            Class<?> fontType = Class.forName(CFONT_CLASS, false, loader);
            Class<?> colorType = Class.forName(CCOLOR_CLASS, false, loader);
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
            resourceCtor = lookup.unreflectConstructor(rCtor)
                    .asType(MethodType.methodType(Object.class, BufferedImage.class, boolean.class));

            Class<?> affineClass = Class.forName("com.live2d.type.CAffine", false, loader);
            Class<?> texture2DClass = Class.forName("com.live2d.graphics3d.texture.GTexture2D", false, loader);
            Constructor<?> tCtor = texture2DClass.getConstructor(resourceClass, affineClass, boolean.class);
            ReflectAccess.open(tCtor);
            textureCtor = lookup.unreflectConstructor(tCtor)
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class, boolean.class));

            Class<?> textureClass = Class.forName("com.live2d.graphics3d.texture.GTexture", false, loader);
            MethodHandle rawFilterMode = unreflect(lookup, textureClass, "getFilterMode");
            Class<?> filterModeClass = rawFilterMode.type().returnType();
            getFilterMode = rawFilterMode.asType(OBJECT_GETTER_TYPE);
            Class<?> minFilterClass = Class.forName("com.live2d.graphics3d.texture.MinFilter", false, loader);
            Class<?> magFilterClass = Class.forName("com.live2d.graphics3d.texture.MagFilter", false, loader);
            setMinFilter = unreflect(lookup, filterModeClass, "setMinFilter", minFilterClass)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            setMagFilter = unreflect(lookup, filterModeClass, "setMagFilter", magFilterClass)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            setPreload = unreflect(lookup, textureClass, "setPreload", boolean.class)
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
            reinitTexture = unreflect(lookup, textureClass, "reinitTexture").asType(VOID_OBJECT_TYPE);
            minFilterLinear = minFilterClass.getField("LINEAR").get(null);
            magFilterLinear = magFilterClass.getField("LINEAR").get(null);

            Class<?> rectClass = Class.forName("com.live2d.graphics3d.type.GRectF", false, loader);
            Constructor<?> rectC = rectClass.getConstructor(float.class, float.class, float.class, float.class);
            ReflectAccess.open(rectC);
            rectCtor = lookup.unreflectConstructor(rectC)
                    .asType(MethodType.methodType(Object.class, float.class, float.class, float.class, float.class));

            Class<?> imageEntityClass = Class.forName("com.live2d.graphics3d.geometry.GImageEntity", false, loader);
            Constructor<?> ieCtor =
                    imageEntityClass.getConstructor(String.class, textureClass, boolean.class, rectClass);
            ReflectAccess.open(ieCtor);
            imageEntityCtor = lookup.unreflectConstructor(ieCtor)
                    .asType(MethodType.methodType(
                            Object.class, String.class, Object.class, boolean.class, Object.class));

            MethodHandle rawMeshRenderer = unreflect(lookup, imageEntityClass, "getMeshRenderer");
            Class<?> rendererClass = rawMeshRenderer.type().returnType();
            getMeshRenderer = rawMeshRenderer.asType(OBJECT_GETTER_TYPE);
            Class<?> layerClass = Class.forName(LAYER_BASE_CLASS, false, loader);
            setSortingLayer = unreflect(lookup, rendererClass, "setSortingLayer", layerClass)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
            setOrderInLayer = unreflect(lookup, rendererClass, "setOrderInLayer", int.class)
                    .asType(MethodType.methodType(void.class, Object.class, int.class));

            Class<?> entityClass = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
            MethodHandle rawTransform = unreflect(lookup, entityClass, "getTransform");
            Class<?> transformClass = rawTransform.type().returnType();
            getTransform = rawTransform.asType(OBJECT_GETTER_TYPE);
            MethodHandle rawPosition = unreflect(lookup, transformClass, "getPosition");
            Class<?> vec3Class = rawPosition.type().returnType();
            getPosition = rawPosition.asType(OBJECT_GETTER_TYPE);
            setVector3 = unreflect(lookup, vec3Class, "set", float.class, float.class, float.class)
                    .asType(MethodType.methodType(void.class, Object.class, float.class, float.class, float.class));
            getParentEntity = unreflect(lookup, entityClass, "getParentEntity").asType(OBJECT_GETTER_TYPE);
            MethodHandle rawChildren = unreflect(lookup, entityClass, "getChildren");
            Class<?> entitiesClass = rawChildren.type().returnType();
            getChildren = rawChildren.asType(OBJECT_GETTER_TYPE);
            addEntity = unreflect(lookup, entitiesClass, "add", entityClass, int.class)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class, int.class));
            setEnabled = unreflect(lookup, entityClass, "setEnabled", boolean.class)
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));

            Class<?> sceneGraphClass = Class.forName("com.live2d.graphics3d.sceneGraph.GSceneGraph", false, loader);
            setVolatile = unreflect(lookup, sceneGraphClass, "setVolatile", entityClass)
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
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
}
