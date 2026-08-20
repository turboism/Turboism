package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.cubism.mesh.MirrorAxisState;
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
        if (!INSTALLED.compareAndSet(null, new Binding(
            axis, ui, enabled, PARTICIPATION.get(), COUNTERPARTS.get()
        ))) {
            throw new IllegalStateException("mesh mirror bridge is already installed");
        }
        replayPendingAttach();
    }

    public static void uninstall() {
        INSTALLED.set(null);
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
        PENDING.set(null);
        CONTROL_ATTACHED.set(false);
        MIRROR_OVERRIDE.set(null);
        EDGE_UNDO_GROUP.remove();
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
        try {
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
            return line == null ? original : MeshMirrorGeometry.hit(
                line, coordinate(source, "getX"), coordinate(source, "getY"), threshold
            );
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return original;
        }
    }

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

    // ------------------------------------------------------------------
    // Mirror-linked deletion (exact 5.2.03 only).
    //
    // 5.3.02 ships this natively: deleting mesh points or edges also deletes
    // their mirror counterparts into the caller's own undo group. 5.2.03 has
    // the mirror math but not the plumbing, so we reproduce exactly what the
    // 5.3.02 bytecode does and nothing more. Every path fails open: the host's
    // own deletion must proceed unchanged even if the mirror step cannot run.
    // ------------------------------------------------------------------

    private static final String MIRROR_OWNER =
        "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.g";
    private static final AtomicReference<Object> MIRROR_OVERRIDE = new AtomicReference<>();
    private static final ThreadLocal<Object> EDGE_UNDO_GROUP = new ThreadLocal<>();
    private static final AtomicReference<RuntimeMeshEditParticipation> PARTICIPATION =
        new AtomicReference<>(new RuntimeMeshEditParticipation());
    private static final AtomicReference<RuntimeMeshMirrorCounterparts> COUNTERPARTS =
        new AtomicReference<>(new RuntimeMeshMirrorCounterparts());

    /** The services a plugin reaches through its context; owned here so the bridge can dispatch. */
    public static RuntimeMeshEditParticipation participation() {
        return PARTICIPATION.get();
    }

    public static RuntimeMeshMirrorCounterparts counterparts() {
        return COUNTERPARTS.get();
    }
    /** Live host objects for the edit currently being dispatched; never escapes to a plugin. */
    private static final ThreadLocal<LiveEdit> LIVE_EDIT = new ThreadLocal<>();

    /** The host handles backing the edit a participant is being asked about. */
    record LiveEdit(Object pack, Object mirror) { }

    static LiveEdit liveEdit() {
        return LIVE_EDIT.get();
    }

    /**
     * Called just before the host's own point deletion. The argument order mirrors the
     * operands already on the stack at that call, so no local slots have to be allocated.
     */
    public static void mirrorDeletePoints(
        final Object sources,
        final Object groupUndo,
        final Object pack
    ) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled
                || pack == null || sources == null || groupUndo == null) return;
            if (!binding.participation.hasParticipants()) {
                diagnostic("PARTICIPATION_SKIPPED reason=NO_PARTICIPANT");
                return;
            }
            final Object mirror = hostMirror(pack);
            if (mirror == null) return;

            final List<Object> sourcePoints = flatten(sources);
            if (sourcePoints.isEmpty()) {
                diagnostic("PARTICIPATION_SKIPPED reason=NO_SOURCES");
                return;
            }
            final MeshEditContribution contribution = dispatch(
                binding, mirror, pack, pointRefs(pack, sourcePoints), List.of()
            );
            if (contribution.isEmpty()) {
                diagnostic("PARTICIPATION_EMPTY kind=POINTS");
                return;
            }
            final int deleted = applyPointDeletions(pack, groupUndo, contribution);
            diagnostic(deleted == 0
                ? "PARTICIPATION_REJECTED kind=POINTS reason=NO_LIVE_MATCH"
                : "PARTICIPATION_APPLIED kind=POINTS count=" + deleted);
        } catch (Throwable failure) {
            diagnostic("PARTICIPATION_FAILED kind=POINTS reason=" + failure.getClass().getName());
        }
    }

    /**
     * Asks participants what else should go with this deletion. The live host handles are
     * published for the duration of the call so the default counterpart resolution can run
     * in-process, and withdrawn immediately after so nothing outlives the dispatch.
     */
    private static MeshEditContribution dispatch(
        final Binding binding,
        final Object mirror,
        final Object pack,
        final List<MeshPointRef> points,
        final List<MeshEdgeRef> edges
    ) throws ReflectiveOperationException {
        final LiveEdit live = new LiveEdit(pack, mirror);
        LIVE_EDIT.set(live);
        try {
            final MirrorAxisState axis = new MirrorAxisState(
                hostMirrorEnabled(mirror, pack), binding.axis.currentAngleDegrees()
            );
            // Only the override path needs a materialised mesh, so only it pays for one.
            final MeshSnapshot mesh = binding.counterparts.hasOverride()
                ? binding.counterparts.snapshot(live)
                : MeshSnapshot.empty();
            return binding.participation.collect(new MeshDeletion(points, edges, axis, mesh));
        } finally {
            LIVE_EDIT.remove();
        }
    }

    /** Contributions are revalidated against the live mesh; a stale id is dropped, never guessed. */
    private static int applyPointDeletions(
        final Object pack,
        final Object groupUndo,
        final MeshEditContribution contribution
    ) throws ReflectiveOperationException {
        final List<Object> live = new ArrayList<>();
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            for (MeshPointRef ref : contribution.points()) {
                final Object point = pointById(mesh, ref.id());
                if (point != null && !containsSame(live, point)) live.add(point);
            }
        }
        if (live.isEmpty()) return 0;
        final Object editMode = call(pack, "aP", new Class<?>[0]);
        final Method delete = declaredMethod(
            editMode.getClass(), "delete_exe", List.class, groupUndo.getClass()
        );
        if (delete == null) {
            diagnostic("PARTICIPATION_REJECTED kind=POINTS reason=NO_DELETE_EXE");
            return 0;
        }
        delete.invoke(editMode, List.of(live), groupUndo);
        return live.size();
    }

    private static List<MeshPointRef> pointRefs(final Object pack, final List<Object> points)
        throws ReflectiveOperationException {
        final List<MeshPointRef> refs = new ArrayList<>();
        for (Object point : points) {
            final int id = pointId(point);
            if (id < 0) continue;
            final Object position = call(point, "getPos", new Class<?>[0]);
            final Object x = position == null ? null : call(position, "getX", new Class<?>[0]);
            final Object y = position == null ? null : call(position, "getY", new Class<?>[0]);
            if (x instanceof Number first && y instanceof Number second) {
                refs.add(new MeshPointRef(id, first.floatValue(), second.floatValue()));
            }
        }
        return refs;
    }

    /** Records the edge action's undo group as the host creates it, for the removal site below. */
    public static void rememberEdgeUndoGroup(final Object groupUndo) {
        EDGE_UNDO_GROUP.set(groupUndo);
    }

    /**
     * Called just before the host's own edge removal. The undo group lives in a local of the
     * host method rather than on the stack here, so it is captured at the point the host
     * creates it and consumed once. Capture happens after the host registers its own snapshot
     * undo, so a single Undo still restores both sides.
     */
    public static void mirrorDeleteEdge(final Object edge, final Object pack) {
        final Object groupUndo = EDGE_UNDO_GROUP.get();
        EDGE_UNDO_GROUP.remove();
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled
                || pack == null || edge == null || groupUndo == null) return;
            if (!binding.participation.hasParticipants()) {
                diagnostic("PARTICIPATION_SKIPPED reason=NO_PARTICIPANT");
                return;
            }
            final Object mirror = hostMirror(pack);
            if (mirror == null) return;

            final Object first = call(edge, "getIndex1", new Class<?>[0]);
            final Object second = call(edge, "getIndex2", new Class<?>[0]);
            if (!(first instanceof Number start) || !(second instanceof Number end)) return;
            if (start.intValue() == end.intValue()) return;
            final MeshEdgeRef source = new MeshEdgeRef(
                start.intValue(), end.intValue(), dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN
            );

            final MeshEditContribution contribution = dispatch(
                binding, mirror, pack, List.of(), List.of(source)
            );
            if (contribution.isEmpty()) {
                diagnostic("PARTICIPATION_EMPTY kind=EDGES");
                return;
            }
            final int removed = applyEdgeDeletions(pack, groupUndo, contribution, source);
            diagnostic(removed == 0
                ? "PARTICIPATION_REJECTED kind=EDGES reason=NO_LIVE_MATCH"
                : "PARTICIPATION_APPLIED kind=EDGES count=" + removed);
        } catch (Throwable failure) {
            diagnostic("PARTICIPATION_FAILED kind=EDGES reason=" + failure.getClass().getName());
        }
    }

    /** Revalidates each contributed edge against the live mesh before removing it. */
    private static int applyEdgeDeletions(
        final Object pack,
        final Object groupUndo,
        final MeshEditContribution contribution,
        final MeshEdgeRef source
    ) throws ReflectiveOperationException {
        int removed = 0;
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            final Object hostEdges = call(mesh, "getEdges", new Class<?>[0]);
            if (!(hostEdges instanceof Iterable<?> iterable)) continue;
            final List<Object> live = new ArrayList<>();
            for (Object candidate : iterable) {
                final Object first = call(candidate, "getIndex1", new Class<?>[0]);
                final Object second = call(candidate, "getIndex2", new Class<?>[0]);
                if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
                for (MeshEdgeRef ref : contribution.edges()) {
                    if (ref.equals(source)) continue;
                    if (ref.startPointId() == Math.min(start.intValue(), end.intValue())
                        && ref.endPointId() == Math.max(start.intValue(), end.intValue())) {
                        live.add(candidate);
                    }
                }
            }
            if (live.isEmpty()) continue;
            final Object handler = call(mesh, "getHandler", new Class<?>[0]);
            if (handler == null) continue;
            final Method remove = declaredMethod(handler.getClass(), "a", List.class);
            if (remove == null) continue;
            final Object undo = remove.invoke(handler, live);
            if (undo == null) continue;
            final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
            if (plusAssign == null) continue;
            plusAssign.invoke(groupUndo, undo);
            removed += live.size();
        }
        return removed;
    }

    /**
     * The counterpart rule copied from 5.3.02: take the source position into the
     * context's mirror space, mirror it with the host's own axis, bring it back,
     * then accept the nearest existing point only inside the host's tolerance.
     */
    static Object counterpartPoint(
        final Object mirror,
        final Object source,
        final Object mesh,
        final Object pack,
        final Object context
    ) throws ReflectiveOperationException {
        final Object position = call(source, "getPos", new Class<?>[0]);
        if (position == null) return null;
        final Class<?> vector = position.getClass();
        final Object local = call(context, "b", new Class<?>[] {vector}, position);
        final Object mirrored = local == null ? null : call(mirror, "a", new Class<?>[] {vector}, local);
        final Object target = mirrored == null ? null : call(context, "a", new Class<?>[] {vector}, mirrored);
        if (target == null) return null;

        Object nearest = null;
        float best = Float.MAX_VALUE;
        for (Object candidate : points(mesh)) {
            final Object candidatePosition = call(candidate, "getPos", new Class<?>[0]);
            if (candidatePosition == null) continue;
            final Object distance = call(candidatePosition, "distance", new Class<?>[] {vector}, target);
            if (!(distance instanceof Number number)) continue;
            final float value = number.floatValue();
            if (value < best) {
                best = value;
                nearest = candidate;
            }
        }
        if (nearest == null) return null;
        final Object scale = call(pack, "aL", new Class<?>[0]);
        if (!(scale instanceof Number number)) return null;
        return best < number.floatValue() ? nearest : null;
    }

    /** Edge counterpart: mirror both endpoints, rebuild the edge id-ordered, keep it only if it exists. */
    static Object counterpartEdge(
        final Object mirror,
        final Object edge,
        final Object mesh,
        final Object pack,
        final Object context
    ) throws ReflectiveOperationException {
        final Object firstIndex = call(edge, "getIndex1", new Class<?>[0]);
        final Object secondIndex = call(edge, "getIndex2", new Class<?>[0]);
        if (!(firstIndex instanceof Number first) || !(secondIndex instanceof Number second)) return null;
        final Object start = pointById(mesh, first.intValue());
        final Object end = pointById(mesh, second.intValue());
        if (start == null || end == null) return null;
        final Object mirroredStart = counterpartPoint(mirror, start, mesh, pack, context);
        final Object mirroredEnd = counterpartPoint(mirror, end, mesh, pack, context);
        if (mirroredStart == null || mirroredEnd == null) return null;
        final int startId = pointId(mirroredStart);
        final int endId = pointId(mirroredEnd);
        if (startId == endId) return null;

        final Object type = call(edge, "getType", new Class<?>[0]);
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : edge.getClass().getConstructors()) {
            final Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length == 3 && parameters[0] == int.class && parameters[1] == int.class) {
                constructor = candidate;
                break;
            }
        }
        if (constructor == null) return null;
        final Object rebuilt = startId < endId
            ? constructor.newInstance(startId, endId, type)
            : constructor.newInstance(endId, startId, type);
        final Object edges = call(mesh, "getEdges", new Class<?>[0]);
        return edges instanceof Collection<?> collection && collection.contains(rebuilt) ? rebuilt : null;
    }

    /**
     * The host's mirror singleton. Note this is the same object whose {@code a(GVector2)}
     * the mirror transformer already rewrites, so the counterpart positions we compute here
     * automatically follow the rotated axis rather than the host's unrotated one.
     */
    static Object hostMirror(final Object pack) {
        final Object override = MIRROR_OVERRIDE.get();
        if (override != null) return override;
        try {
            return Class.forName(MIRROR_OWNER, false, pack.getClass().getClassLoader())
                .getField("a").get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The mesh edit mode behind the panel the host last handed us, or null when no mesh edit is
     * open. Navigated through the same property chain the pivot resolution already uses.
     */
    static Object activeMeshEditMode() {
        try {
            final Object panel = CURRENT_PANEL.get();
            if (panel == null) return null;
            final Object toolMode = property(panel, "toolMode");
            final Object controller = invoke(toolMode, "getCtrl$cubism");
            final Object completePack = property(controller, "completePack");
            final Object viewContext = property(completePack, "currentViewContext");
            return property(viewContext, "currentEditMode");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Starts one undoable step through the host's own mechanism; never invents an undo group. */
    static Object beginUndoGroup(final Object editMode, final String label) {
        try {
            return call(editMode, "beginEdit", new Class<?>[] {String.class}, label);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    static void collectSnapshot(
        final Object mesh,
        final List<MeshPointRef> points,
        final List<MeshEdgeRef> edges
    ) throws ReflectiveOperationException {
        for (Object point : points(mesh)) {
            final int id = pointId(point);
            if (id < 0) continue;
            final Object position = call(point, "getPos", new Class<?>[0]);
            final Object x = position == null ? null : call(position, "getX", new Class<?>[0]);
            final Object y = position == null ? null : call(position, "getY", new Class<?>[0]);
            if (x instanceof Number first && y instanceof Number second) {
                points.add(new MeshPointRef(id, first.floatValue(), second.floatValue()));
            }
        }
        final Object hostEdges = call(mesh, "getEdges", new Class<?>[0]);
        if (!(hostEdges instanceof Iterable<?> iterable)) return;
        for (Object edge : iterable) {
            final Object first = call(edge, "getIndex1", new Class<?>[0]);
            final Object second = call(edge, "getIndex2", new Class<?>[0]);
            if (first instanceof Number start && second instanceof Number end
                && start.intValue() != end.intValue()) {
                edges.add(new MeshEdgeRef(
                    start.intValue(), end.intValue(), dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN
                ));
            }
        }
    }

    /** Removes the referenced edges through the host's undo-aware handler; returns how many. */
    static int removeEdgesInto(
        final Object mesh,
        final List<MeshEdgeRef> refs,
        final Object groupUndo
    ) throws ReflectiveOperationException {
        final Object hostEdges = call(mesh, "getEdges", new Class<?>[0]);
        if (!(hostEdges instanceof Iterable<?> iterable)) return 0;
        final List<Object> live = new ArrayList<>();
        for (Object candidate : iterable) {
            final Object first = call(candidate, "getIndex1", new Class<?>[0]);
            final Object second = call(candidate, "getIndex2", new Class<?>[0]);
            if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
            final int low = Math.min(start.intValue(), end.intValue());
            final int high = Math.max(start.intValue(), end.intValue());
            for (MeshEdgeRef ref : refs) {
                if (ref.startPointId() == low && ref.endPointId() == high) live.add(candidate);
            }
        }
        if (live.isEmpty()) return 0;
        final Object handler = call(mesh, "getHandler", new Class<?>[0]);
        if (handler == null) return 0;
        final Method remove = declaredMethod(handler.getClass(), "a", List.class);
        if (remove == null) return 0;
        final Object undo = remove.invoke(handler, live);
        if (undo == null) return 0;
        final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
        if (plusAssign == null) return 0;
        plusAssign.invoke(groupUndo, undo);
        return live.size();
    }

    /** Test seam: the exact host mirror class cannot exist on an offline classpath. */
    static void mirrorForTesting(final Object mirror) {
        MIRROR_OVERRIDE.set(mirror);
    }

    /** Uses the host's own enable predicate; we never invent one. */
    static boolean hostMirrorEnabled(final Object mirror, final Object pack) {
        try {
            final Object editMode = call(pack, "aP", new Class<?>[0]);
            if (editMode != null) {
                for (Method method : mirror.getClass().getMethods()) {
                    if (!method.getName().equals("a") || method.getParameterCount() != 1) continue;
                    if (method.getReturnType() != boolean.class) continue;
                    if (!method.getParameterTypes()[0].isInstance(editMode)) continue;
                    return Boolean.TRUE.equals(method.invoke(mirror, editMode));
                }
            }
            final Method noArg = declaredMethod(mirror.getClass(), "a");
            return noArg != null && noArg.getReturnType() == boolean.class
                && Boolean.TRUE.equals(noArg.invoke(mirror));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static List<?> contexts(final Object pack) throws ReflectiveOperationException {
        final Object value = call(pack, "aT", new Class<?>[0]);
        return value instanceof List<?> list ? list : List.of();
    }

    static List<?> points(final Object mesh) throws ReflectiveOperationException {
        final Object value = call(mesh, "getAllPointRef", new Class<?>[0]);
        return value instanceof List<?> list ? list : List.of();
    }

    static Object pointById(final Object mesh, final int id) throws ReflectiveOperationException {
        for (Object candidate : points(mesh)) {
            if (pointId(candidate) == id) return candidate;
        }
        return null;
    }

    static int pointId(final Object point) throws ReflectiveOperationException {
        final Object value = call(point, "b", new Class<?>[0]);
        return value instanceof Number number ? number.intValue() : Integer.MIN_VALUE;
    }

    private static boolean containsSame(final List<Object> collected, final Object candidate) {
        for (Object existing : collected) if (existing == candidate) return true;
        return false;
    }

    /** Flattens the host's list-of-lists deletion argument, and tolerates a plain list. */
    private static List<Object> flatten(final Object sources) {
        final List<Object> flattened = new ArrayList<>();
        if (!(sources instanceof Collection<?> outer)) return flattened;
        for (Object element : outer) {
            if (element instanceof Collection<?> inner) flattened.addAll(inner);
            else if (element != null) flattened.add(element);
        }
        return flattened;
    }

    /**
     * Strict lookup by exact parameter types. The host mirror class overloads the
     * name {@code a} many times over unrelated types, so matching on argument count
     * alone could invoke the wrong one against user mesh data.
     */
    static Method declaredMethod(
        final Class<?> owner,
        final String name,
        final Class<?>... parameters
    ) {
        Class<?> type = owner;
        while (type != null) {
            try {
                final Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final Class<?>[] actual = method.getParameterTypes();
            if (actual.length != parameters.length) continue;
            boolean matches = true;
            for (int index = 0; index < actual.length; index++) {
                if (!actual[index].isAssignableFrom(parameters[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) return method;
        }
        return null;
    }

    static Object call(
        final Object target,
        final String name,
        final Class<?>[] parameters,
        final Object... arguments
    ) throws ReflectiveOperationException {
        if (target == null) return null;
        final Method method = declaredMethod(target.getClass(), name, parameters);
        return method == null ? null : method.invoke(target, arguments);
    }

    private record Binding(
        RuntimeMeshMirrorAxisService axis,
        RuntimeMeshEditUiService ui,
        boolean enabled,
        RuntimeMeshEditParticipation participation,
        RuntimeMeshMirrorCounterparts counterparts
    ) { }

    /** Exact references delivered by the accepted public callback; never resolved by search. */
    private record PendingAttach(Object panel, Object widget) { }
}
