package dev.turboism.adapter.cubism.mesh;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditTool;
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
    /** Movement success is high-frequency; report presence once per bridge installation. */
    private static final AtomicBoolean MOVE_APPLIED_REPORTED = new AtomicBoolean();
    private static final Consumer<String> DEFAULT_DIAGNOSTIC = ignored -> { };
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

    /** Installs an enabled bridge using the supplied axis and UI services. */
    public static void install(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui
    ) {
        install(axis, ui, true);
    }

    /** Installs a bridge using the supplied services and enabled-state policy. */
    public static void install(
        final RuntimeMeshMirrorAxisService axis,
        final RuntimeMeshEditUiService ui,
        final boolean enabled
    ) {
        if (!INSTALLED.compareAndSet(null, new Binding(
            axis, ui, enabled, PARTICIPATION, COUNTERPARTS
        ))) {
            throw new IllegalStateException("mesh mirror bridge is already installed");
        }
        replayPendingAttach();
    }

    /** Revokes the bridge and clears all session-scoped host state. */
    public static void uninstall() {
        INSTALLED.set(null);
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
        PENDING.set(null);
        CONTROL_ATTACHED.set(false);
        MOVE_APPLIED_REPORTED.set(false);
        MIRROR_OVERRIDE.set(null);
        EDGE_UNDO_GROUP.remove();
        LIVE_EDIT.remove();
        DEFAULT_COUNTERPART_POINTS.remove();
        DEFAULT_COUNTERPART_EDGES.remove();
        DEFAULT_CONTRIBUTIONS.remove();
        COLLECTED_CONTRIBUTIONS.remove();
        PARTICIPATION.resetSession();
        COUNTERPARTS.resetSession();
        TOOL_ELIGIBILITY.resetSession();
        MOVE_PARTICIPATION.resetSession();
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

    /** Returns the reflected source point when the installed bridge can resolve one. */
    public static Object adjustPoint(
        final Object original,
        final Object mirrorState,
        final Object source
    ) {
        return adjust(original, mirrorState, source, false);
    }

    /** Returns the source point projected onto the resolved mirror axis when possible. */
    public static Object adjustAxisPoint(
        final Object original,
        final Object mirrorState,
        final Object source
    ) {
        return adjust(original, mirrorState, source, true);
    }

    /** Widens the exact 5.2.03 native predicate only for plugin-registered host-neutral tools. */
    public static boolean adjustToolEligibility(final boolean original, final Object nativeTool) {
        if (original || INSTALLED.get() == null || nativeTool == null) return original;
        try {
            final MeshEditTool tool = nativeTool instanceof Enum<?> value
                ? meshEditTool(value.name())
                : MeshEditTool.UNKNOWN;
            return tool != MeshEditTool.UNKNOWN && TOOL_ELIGIBILITY.isExtended(tool);
        } catch (Throwable failure) {
            diagnostic("TOOL_ELIGIBILITY_FAILED reason=" + failure.getClass().getName());
            return original;
        }
    }

    private static MeshEditTool meshEditTool(final String name) {
        try {
            return MeshEditTool.valueOf(name);
        } catch (IllegalArgumentException failure) {
            return MeshEditTool.UNKNOWN;
        }
    }

    /** Extends native hit detection to the resolved mirror line when a bridge is enabled. */
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

    /** Records or attaches the host mirror control, preserving the original widget result. */
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

    /** Updates the mirror-axis pivot observed from the host canvas. */
    public static void observePivot(final float pivotX, final float pivotY) {
        final Binding binding = INSTALLED.get();
        if (binding != null && binding.enabled) binding.axis.observePivot(pivotX, pivotY);
    }

    /** Clears the mirror-axis pivot held for the active host context. */
    public static void clearPivot() {
        final Binding binding = INSTALLED.get();
        if (binding != null) binding.axis.clearPivot();
    }

    /** Draws the resolved mirror axis with host-native drawing facilities when available. */
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


    /** Clears cached host UI and context identities after a context transition. */
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
    /** Exact host edges selected by the default resolver, scoped to the current dispatch only. */
    private static final ThreadLocal<List<Object>> DEFAULT_COUNTERPART_EDGES = new ThreadLocal<>();
    private static final RuntimeMeshEditParticipation PARTICIPATION =
        new RuntimeMeshEditParticipation();
    private static final RuntimeMeshMirrorCounterparts COUNTERPARTS =
        new RuntimeMeshMirrorCounterparts();
    private static final RuntimeMeshMirrorToolEligibility TOOL_ELIGIBILITY =
        new RuntimeMeshMirrorToolEligibility();
    private static final RuntimeMeshMirrorMoveParticipation MOVE_PARTICIPATION =
        new RuntimeMeshMirrorMoveParticipation();

    /** The services a plugin reaches through its context; owned here so the bridge can dispatch. */
    public static RuntimeMeshEditParticipation participation() {
        return PARTICIPATION;
    }

    /** Returns the plugin-facing resolver registry for mirror counterparts. */
    public static RuntimeMeshMirrorCounterparts counterparts() {
        return COUNTERPARTS;
    }

    /** Returns the registry that extends native mirror-tool eligibility. */
    public static RuntimeMeshMirrorToolEligibility toolEligibility() {
        return TOOL_ELIGIBILITY;
    }

    /** Returns the registry for participant-controlled mirror movement. */
    public static RuntimeMeshMirrorMoveParticipation moveParticipation() {
        return MOVE_PARTICIPATION;
    }

    /** Live host objects for the edit currently being dispatched; never escapes to a plugin. */
    private static final ThreadLocal<LiveEdit> LIVE_EDIT = new ThreadLocal<>();
    /** Exact host points selected by the default resolver, scoped to the current dispatch only. */
    private static final ThreadLocal<List<Object>> DEFAULT_COUNTERPART_POINTS = new ThreadLocal<>();
    /** Contribution objects produced by the default resolver, tracked by identity for provenance. */
    private static final ThreadLocal<List<MeshEditContribution>> DEFAULT_CONTRIBUTIONS = new ThreadLocal<>();
    /** Successful participant outputs in callback order, before the collector flattens their values. */
    private static final ThreadLocal<List<MeshEditContribution>> COLLECTED_CONTRIBUTIONS = new ThreadLocal<>();

    /** The host handles backing the edit a participant is being asked about. */
    record LiveEdit(
        Object pack,
        Object mirror,
        List<Object> sourcePoints,
        List<Object> sourceEdges,
        boolean pointSourcesById,
        boolean endpointEdgeSources
    ) { }

    record ProvenanceMark(int points, int edges, int defaults, int collected) { }

    private record DispatchState(
        LiveEdit liveEdit,
        List<Object> points,
        List<Object> edges,
        List<MeshEditContribution> defaults,
        List<MeshEditContribution> collected
    ) { }

    static void rememberDefaultCounterpart(final Object point) {
        rememberIdentity(DEFAULT_COUNTERPART_POINTS, point);
    }

    static void rememberDefaultCounterpartEdge(final Object edge) {
        rememberIdentity(DEFAULT_COUNTERPART_EDGES, edge);
    }

    static void rememberDefaultContribution(final MeshEditContribution contribution) {
        rememberContribution(DEFAULT_CONTRIBUTIONS, contribution);
    }

    static void rememberCollectedContribution(final MeshEditContribution contribution) {
        rememberContribution(COLLECTED_CONTRIBUTIONS, contribution);
    }

    private static void rememberContribution(
        final ThreadLocal<List<MeshEditContribution>> target,
        final MeshEditContribution contribution
    ) {
        List<MeshEditContribution> contributions = target.get();
        if (contributions == null) {
            contributions = new ArrayList<>();
            target.set(contributions);
        }
        contributions.add(contribution);
    }

    private static void rememberIdentity(
        final ThreadLocal<List<Object>> target,
        final Object value
    ) {
        List<Object> values = target.get();
        if (values == null) {
            values = new ArrayList<>();
            target.set(values);
        }
        if (!containsSame(values, value)) values.add(value);
    }

    static LiveEdit liveEdit() {
        return LIVE_EDIT.get();
    }

    static boolean participationDispatchActive() {
        return LIVE_EDIT.get() != null;
    }

    static ProvenanceMark markDefaultProvenance() {
        return new ProvenanceMark(
            size(DEFAULT_COUNTERPART_POINTS.get()),
            size(DEFAULT_COUNTERPART_EDGES.get()),
            size(DEFAULT_CONTRIBUTIONS.get()),
            size(COLLECTED_CONTRIBUTIONS.get())
        );
    }

    static void restoreDefaultProvenance(final ProvenanceMark mark) {
        truncate(DEFAULT_COUNTERPART_POINTS.get(), mark.points());
        truncate(DEFAULT_COUNTERPART_EDGES.get(), mark.edges());
        truncate(DEFAULT_CONTRIBUTIONS.get(), mark.defaults());
        truncate(COLLECTED_CONTRIBUTIONS.get(), mark.collected());
    }

    private static int size(final List<?> values) {
        return values == null ? 0 : values.size();
    }

    private static void truncate(final List<?> values, final int size) {
        if (values != null && values.size() > size) values.subList(size, values.size()).clear();
    }

    private static DispatchState pushDispatchState() {
        final DispatchState previous = new DispatchState(
            LIVE_EDIT.get(),
            DEFAULT_COUNTERPART_POINTS.get(),
            DEFAULT_COUNTERPART_EDGES.get(),
            DEFAULT_CONTRIBUTIONS.get(),
            COLLECTED_CONTRIBUTIONS.get()
        );
        LIVE_EDIT.remove();
        DEFAULT_COUNTERPART_POINTS.remove();
        DEFAULT_COUNTERPART_EDGES.remove();
        DEFAULT_CONTRIBUTIONS.remove();
        COLLECTED_CONTRIBUTIONS.remove();
        return previous;
    }

    private static void restoreDispatchState(final DispatchState previous) {
        restoreThreadLocal(LIVE_EDIT, previous.liveEdit());
        restoreThreadLocal(DEFAULT_COUNTERPART_POINTS, previous.points());
        restoreThreadLocal(DEFAULT_COUNTERPART_EDGES, previous.edges());
        restoreThreadLocal(DEFAULT_CONTRIBUTIONS, previous.defaults());
        restoreThreadLocal(COLLECTED_CONTRIBUTIONS, previous.collected());
    }

    private static <T> void restoreThreadLocal(final ThreadLocal<T> local, final T value) {
        if (value == null) local.remove(); else local.set(value);
    }

    /**
     * Moves unselected mirror counterparts immediately before the exact 5.2.03 host loop moves
     * its selected source points. The enclosing native drag already owns the whole-mesh Undo
     * snapshots, so this deliberately opens no second edit group.
     */
    public static void mirrorMoveSelected(final Object pack) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled || pack == null) return;
            if (!MOVE_PARTICIPATION.hasParticipants()) {
                diagnostic("MOVE_PARTICIPATION_SKIPPED reason=NO_PARTICIPANT");
                return;
            }
            final Object mirror = hostMirror(pack);
            if (mirror == null || !hostMirrorEnabled(mirror, pack)) return;
            final Object scale = call(pack, "aL", new Class<?>[0]);
            if (!(scale instanceof Number number)) return;
            final float tolerance = 20.0f * number.floatValue();
            int moved = 0;
            for (Object context : contexts(pack)) {
                final Object delta = call(context, "p", new Class<?>[0]);
                final Object mesh = call(context, "b", new Class<?>[0]);
                if (delta == null || mesh == null) continue;
                final Object selection = call(mesh, "getSelection", new Class<?>[0]);
                final Object selector = call(selection, "getPointSelector", new Class<?>[0]);
                if (!(selector instanceof Iterable<?> selected)) continue;

                final List<Object> selectedRefs = new ArrayList<>();
                final List<Integer> selectedIds = new ArrayList<>();
                for (Object reference : selected) {
                    selectedRefs.add(reference);
                    final Object live = compatiblePoint(mesh, reference);
                    if (live != null) selectedIds.add(pointId(live));
                }
                for (Object reference : selectedRefs) {
                    final Object source = compatiblePoint(mesh, reference);
                    if (source == null) continue;
                    final float weight = selectionWeight(selector, reference);
                    final Object counterpart = counterpartPoint(
                        mirror, source, mesh, pack, context, tolerance
                    );
                    if (counterpart == null || selectedIds.contains(pointId(counterpart))) continue;
                    final Object sourcePosition = call(source, "getPos", new Class<?>[0]);
                    if (sourcePosition == null) continue;
                    final Object target = call(
                        sourcePosition, "plus", new Class<?>[] {delta.getClass()}, delta
                    );
                    final Object mirroredTarget = mirrorPoint(context, mirror, target);
                    if (mirroredTarget == null) continue;
                    final Method move = movePointMethod(counterpart.getClass(), mirroredTarget.getClass());
                    if (move == null) continue;
                    move.invoke(counterpart, mirroredTarget, weight);
                    moved++;
                }
            }
            if (moved > 0 && MOVE_APPLIED_REPORTED.compareAndSet(false, true)) {
                diagnostic("MOVE_PARTICIPATION_APPLIED count=" + moved);
            }
        } catch (Throwable failure) {
            diagnostic("MOVE_PARTICIPATION_FAILED reason=" + failure.getClass().getName());
        }
    }

    static Object compatiblePoint(final Object mesh, final Object reference)
        throws ReflectiveOperationException {
        if (reference == null) return null;
        for (Method method : mesh.getClass().getMethods()) {
            if (!method.getName().equals("getCompatiblePointRef") || method.getParameterCount() != 1) {
                continue;
            }
            if (!method.getParameterTypes()[0].isInstance(reference)) continue;
            return method.invoke(mesh, reference);
        }
        return containsIdentity(points(mesh), reference) ? reference : null;
    }

    private static float selectionWeight(final Object selector, final Object reference)
        throws ReflectiveOperationException {
        for (Method method : selector.getClass().getMethods()) {
            if (!method.getName().equals("getWeight") || method.getParameterCount() != 2) continue;
            final Class<?>[] parameters = method.getParameterTypes();
            if (!parameters[0].isInstance(reference) || parameters[1] != float.class) continue;
            final Object value = method.invoke(selector, reference, 0.0f);
            return value instanceof Number number ? number.floatValue() : 0.0f;
        }
        throw new NoSuchMethodException("mesh point selection weight");
    }

    private static Object mirrorPoint(
        final Object context,
        final Object mirror,
        final Object point
    ) throws ReflectiveOperationException {
        if (point == null) return null;
        final Class<?> vector = point.getClass();
        final Object local = call(context, "b", new Class<?>[] {vector}, point);
        final Object mirrored = local == null ? null : call(mirror, "a", new Class<?>[] {vector}, local);
        return mirrored == null ? null : call(context, "a", new Class<?>[] {vector}, mirrored);
    }

    /**
     * Point-tool route: native 5.3 deletes mirror points and every incident mirror edge.
     * The argument order mirrors the operands already on the stack at the host deletion call.
     */
    public static void mirrorDeletePointAction(
        final Object sources,
        final Object groupUndo,
        final Object pack
    ) {
        mirrorDeletePoints(sources, groupUndo, pack, true);
    }

    /** Deletes mirror points for an edge-driven host deletion within the supplied undo group. */
    public static void mirrorDeletePoints(
        final Object sources,
        final Object groupUndo,
        final Object pack
    ) {
        mirrorDeletePoints(sources, groupUndo, pack, false);
    }

    /** Eraser route: source points are candidate-mesh handles, resolved by id in each live mesh. */
    public static void mirrorDeleteEraserPoints(
        final Object sources,
        final Object pack,
        final Object groupUndo
    ) {
        mirrorDeletePoints(sources, groupUndo, pack, true, true);
    }

    private static void mirrorDeletePoints(
        final Object sources,
        final Object groupUndo,
        final Object pack,
        final boolean removeIncidentEdges
    ) {
        mirrorDeletePoints(sources, groupUndo, pack, removeIncidentEdges, false);
    }

    private static void mirrorDeletePoints(
        final Object sources,
        final Object groupUndo,
        final Object pack,
        final boolean removeIncidentEdges,
        final boolean pointSourcesById
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
            final List<MeshPointRef> sourceRefs = pointRefs(pack, sourcePoints);
            final DispatchState previous = pushDispatchState();
            try {
                final MeshEditContribution contribution = pointSourcesById
                    ? dispatch(
                        binding, mirror, pack, sourcePoints, List.of(), sourceRefs, List.of(),
                        true, false
                    )
                    : dispatch(
                        binding, mirror, pack, sourcePoints, List.of(), sourceRefs, List.of()
                    );
                if (contribution.isEmpty()) {
                    diagnostic("PARTICIPATION_EMPTY kind=POINTS");
                    return;
                }
                final PointDeletionResult deleted = applyPointDeletions(
                    pack, groupUndo, contribution, sourcePoints, removeIncidentEdges
                );
                diagnostic(deleted.points() == 0
                    ? "PARTICIPATION_REJECTED kind=POINTS reason=NO_LIVE_MATCH"
                    : "PARTICIPATION_APPLIED kind=POINTS count=" + deleted.points()
                        + " incidentEdges=" + deleted.edges());
            } finally {
                restoreDispatchState(previous);
            }
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
        final List<Object> sourcePoints,
        final List<Object> sourceEdges,
        final List<MeshPointRef> points,
        final List<MeshEdgeRef> edges
    ) throws ReflectiveOperationException {
        return dispatch(
            binding, mirror, pack, sourcePoints, sourceEdges, points, edges, false, false
        );
    }

    private static MeshEditContribution dispatch(
        final Binding binding,
        final Object mirror,
        final Object pack,
        final List<Object> sourcePoints,
        final List<Object> sourceEdges,
        final List<MeshPointRef> points,
        final List<MeshEdgeRef> edges,
        final boolean pointSourcesById,
        final boolean endpointEdgeSources
    ) throws ReflectiveOperationException {
        final LiveEdit live = new LiveEdit(
            pack, mirror, List.copyOf(sourcePoints), List.copyOf(sourceEdges),
            pointSourcesById, endpointEdgeSources
        );
        final LiveEdit previous = LIVE_EDIT.get();
        LIVE_EDIT.set(live);
        try {
            final MirrorAxisState axis = new MirrorAxisState(
                hostMirrorEnabled(mirror, pack), binding.axis.currentAngleDegrees()
            );
            return binding.participation.collect(
                new MeshDeletion(points, edges, axis, MeshSnapshot.empty())
            );
        } finally {
            restoreThreadLocal(LIVE_EDIT, previous);
        }
    }

    /** Contributions are revalidated against the live mesh; a stale id is dropped, never guessed. */
    private static PointDeletionResult applyPointDeletions(
        final Object pack,
        final Object groupUndo,
        final MeshEditContribution contribution,
        final List<Object> sources,
        final boolean removeIncidentEdges
    ) throws ReflectiveOperationException {
        final List<Object> live = new ArrayList<>();
        final List<Object> resolved = DEFAULT_COUNTERPART_POINTS.get();
        if (resolved != null) live.addAll(resolved);
        final List<MeshPointRef> custom = customPointContributions(contribution);
        final List<Object> customMatches = uniqueCustomPointMatches(pack, custom, sources);
        live.addAll(customMatches);
        if (live.isEmpty()) return new PointDeletionResult(0, 0);

        final int edges = removeIncidentEdges
            ? removeIncidentEdgesInto(pack, live, groupUndo)
            : 0;
        final Object editMode = call(pack, "aP", new Class<?>[0]);
        final Method delete = declaredMethod(
            editMode.getClass(), "delete_exe", List.class, groupUndo.getClass()
        );
        if (delete == null) {
            diagnostic("PARTICIPATION_REJECTED kind=POINTS reason=NO_DELETE_EXE");
            return new PointDeletionResult(0, edges);
        }
        delete.invoke(editMode, List.of(live), groupUndo);
        return new PointDeletionResult(live.size(), edges);
    }

    private static int removeIncidentEdgesInto(
        final Object pack,
        final List<Object> points,
        final Object groupUndo
    ) throws ReflectiveOperationException {
        int removed = 0;
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            final List<Integer> ids = new ArrayList<>();
            for (Object point : points) {
                if (containsIdentity(NativeMeshMirrorBridge.points(mesh), point)) {
                    ids.add(pointId(point));
                }
            }
            if (ids.isEmpty()) continue;
            final List<Object> incident = new ArrayList<>();
            for (Object edge : edges(mesh)) {
                final Object first = call(edge, "getIndex1", new Class<?>[0]);
                final Object second = call(edge, "getIndex2", new Class<?>[0]);
                if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
                if ((ids.contains(start.intValue()) || ids.contains(end.intValue()))
                    && !containsSame(incident, edge)) {
                    incident.add(edge);
                }
            }
            removed += removeLiveEdgesInto(mesh, incident, groupUndo);
        }
        return removed;
    }

    private record PointDeletionResult(int points, int edges) { }

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

    /** Low-volume route marker: emitted only when the exact transformed edge action executes. */
    public static void edgeDeleteActionEntered() {
        diagnostic("EDGE_DELETE_ACTION_ENTER");
    }

    /** Low-volume route marker for the real brush/drag eraser action. */
    public static void eraserDeleteActionEntered() {
        diagnostic("ERASER_DELETE_ACTION_ENTER");
    }

    /**
     * Mirrors the exact 5.3.02 eraser structure: counterpart edges are resolved and removed
     * separately through every context's live edit mesh, and every child Undo is attached to the
     * current outer "Eraser" group. The caller's source list is never changed; the original
     * 5.2.03 handler call therefore proceeds exactly as before after this method returns.
     */
    public static void mirrorDeleteEraserEdges(
        final Object sources,
        final Object pack,
        final Object groupUndo
    ) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null) {
                diagnostic("ERASER_DELETE_SKIPPED reason=UNBOUND");
                return;
            }
            if (!binding.enabled) {
                diagnostic("ERASER_DELETE_SKIPPED reason=DISABLED");
                return;
            }
            if (pack == null) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NULL_PACK");
                return;
            }
            if (groupUndo == null) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_UNDO_GROUP");
                return;
            }
            if (!(sources instanceof List<?> rawSources)) {
                diagnostic("ERASER_DELETE_SKIPPED reason=INVALID_SOURCES");
                return;
            }
            if (!binding.participation.hasParticipants()) {
                diagnostic("PARTICIPATION_SKIPPED reason=NO_PARTICIPANT");
                return;
            }
            final Object mirror = hostMirror(pack);
            if (mirror == null) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_MIRROR");
                return;
            }
            if (!hostMirrorEnabled(mirror, pack)) {
                diagnostic("ERASER_DELETE_SKIPPED reason=MIRROR_DISABLED");
                return;
            }

            final List<Object> sourceEdges = new ArrayList<>();
            for (Object edge : rawSources) if (edge != null) sourceEdges.add(edge);
            if (sourceEdges.isEmpty()) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_SOURCES");
                return;
            }
            final List<MeshEdgeRef> sourceRefs = edgeRefs(sourceEdges);
            if (sourceRefs.isEmpty()) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_SOURCE_REFS");
                return;
            }

            final DispatchState previous = pushDispatchState();
            try {
                final MeshEditContribution contribution = dispatch(
                    binding, mirror, pack, List.of(), sourceEdges, List.of(), sourceRefs,
                    false, true
                );
                if (contribution.isEmpty()) {
                    diagnostic("PARTICIPATION_EMPTY kind=ERASER_EDGES");
                    return;
                }
                final int removed = applyEraserEdgeDeletions(
                    pack, mirror, groupUndo, contribution, sourceEdges
                );
                diagnostic(removed == 0
                    ? "PARTICIPATION_REJECTED kind=ERASER_EDGES reason=NO_LIVE_MATCH"
                    : "PARTICIPATION_APPLIED kind=ERASER_EDGES count=" + removed);
            } finally {
                restoreDispatchState(previous);
            }
        } catch (Throwable failure) {
            diagnostic("PARTICIPATION_FAILED kind=ERASER_EDGES reason="
                + failure.getClass().getName());
        }
    }

    private static List<MeshEdgeRef> edgeRefs(final List<Object> edges)
        throws ReflectiveOperationException {
        final List<MeshEdgeRef> refs = new ArrayList<>();
        for (Object edge : edges) {
            final Object first = call(edge, "getIndex1", new Class<?>[0]);
            final Object second = call(edge, "getIndex2", new Class<?>[0]);
            if (first instanceof Number start && second instanceof Number end
                && start.intValue() != end.intValue()) {
                refs.add(new MeshEdgeRef(
                    start.intValue(), end.intValue(),
                    dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN
                ));
            }
        }
        return refs;
    }

    /** Native-style endpoint reconstruction for eraser sources, plus plugin custom output. */
    private static int applyEraserEdgeDeletions(
        final Object pack,
        final Object mirror,
        final Object groupUndo,
        final MeshEditContribution contribution,
        final List<Object> sources
    ) throws ReflectiveOperationException {
        final List<MeshEdgeRef> custom = customEdgeContributions(contribution);
        int removed = 0;
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            final List<Object> live = new ArrayList<>();
            for (Object source : sources) {
                final Object counterpart = counterpartEdge(
                    mirror, source, mesh, pack, context
                );
                if (counterpart != null && !containsSame(live, counterpart)) {
                    live.add(counterpart);
                }
            }
            for (MeshEdgeRef ref : custom) {
                for (Object candidate : edges(mesh)) {
                    if (!edgeMatches(candidate, ref) || containsSame(live, candidate)) continue;
                    live.add(candidate);
                }
            }
            removed += removeLiveEdgesInto(mesh, live, groupUndo);
        }
        return removed;
    }

    private static int removeLiveEdgesInto(
        final Object mesh,
        final List<Object> live,
        final Object groupUndo
    ) throws ReflectiveOperationException {
        if (live.isEmpty()) return 0;
        final Object handler = call(mesh, "getHandler", new Class<?>[0]);
        if (handler == null) return 0;
        final Method remove = declaredMethod(handler.getClass(), "a", List.class);
        if (remove == null || !hasUndoAttachmentMethod(groupUndo)) return 0;
        final Object undo = remove.invoke(handler, live);
        if (undo == null) return 0;
        final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
        if (plusAssign == null) throw new NoSuchMethodException("mesh edge undo attachment");
        plusAssign.invoke(groupUndo, undo);
        return live.size();
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
            if (binding == null) {
                diagnostic("EDGE_DELETE_SKIPPED reason=UNBOUND");
                return;
            }
            if (!binding.enabled) {
                diagnostic("EDGE_DELETE_SKIPPED reason=DISABLED");
                return;
            }
            if (pack == null) {
                diagnostic("EDGE_DELETE_SKIPPED reason=NULL_PACK");
                return;
            }
            if (edge == null) {
                diagnostic("EDGE_DELETE_SKIPPED reason=NULL_EDGE");
                return;
            }
            if (groupUndo == null) {
                diagnostic("EDGE_DELETE_SKIPPED reason=NO_UNDO_GROUP");
                return;
            }
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

            final DispatchState previous = pushDispatchState();
            try {
                final MeshEditContribution contribution = dispatch(
                    binding, mirror, pack, List.of(), List.of(edge), List.of(), List.of(source)
                );
                if (contribution.isEmpty()) {
                    diagnostic("PARTICIPATION_EMPTY kind=EDGES");
                    return;
                }
                final int removed = applyEdgeDeletions(pack, groupUndo, contribution, edge);
                diagnostic(removed == 0
                    ? "PARTICIPATION_REJECTED kind=EDGES reason=NO_LIVE_MATCH"
                    : "PARTICIPATION_APPLIED kind=EDGES count=" + removed);
            } finally {
                restoreDispatchState(previous);
            }
        } catch (Throwable failure) {
            diagnostic("PARTICIPATION_FAILED kind=EDGES reason=" + failure.getClass().getName());
        }
    }

    /** Revalidates each contributed edge against the live mesh before removing it. */
    private static int applyEdgeDeletions(
        final Object pack,
        final Object groupUndo,
        final MeshEditContribution contribution,
        final Object source
    ) throws ReflectiveOperationException {
        int removed = 0;
        final List<Object> resolved = DEFAULT_COUNTERPART_EDGES.get();
        final List<MeshEdgeRef> custom = customEdgeContributions(contribution);
        final List<Object> customMatches = uniqueCustomEdgeMatches(pack, custom, source);
        for (Object context : contexts(pack)) {
            final Object mesh = call(context, "b", new Class<?>[0]);
            if (mesh == null) continue;
            final Object hostEdges = call(mesh, "getEdges", new Class<?>[0]);
            if (!(hostEdges instanceof Iterable<?> iterable)) continue;
            final List<Object> live = new ArrayList<>();
            if (resolved != null) {
                for (Object edge : resolved) if (containsIdentity(iterable, edge)) live.add(edge);
            }
            for (Object edge : customMatches) {
                if (containsIdentity(iterable, edge) && !containsSame(live, edge)) live.add(edge);
            }
            if (live.isEmpty()) continue;
            final Object handler = call(mesh, "getHandler", new Class<?>[0]);
            if (handler == null) continue;
            final Method remove = declaredMethod(handler.getClass(), "a", List.class);
            if (remove == null || !hasUndoAttachmentMethod(groupUndo)) continue;
            final Object undo = remove.invoke(handler, live);
            if (undo == null) continue;
            final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
            if (plusAssign == null) throw new NoSuchMethodException("mesh edge undo attachment");
            plusAssign.invoke(groupUndo, undo);
            removed += live.size();
        }
        return removed;
    }

    /**
     * Resolves a host-neutral point reference only when it names one distinct live non-source
     * object across the whole edit. Point ids are mesh-local, so multiple matches are ambiguous
     * and must fail closed rather than widening a plugin contribution.
     */
    private static List<Object> uniqueCustomPointMatches(
        final Object pack,
        final List<MeshPointRef> refs,
        final List<Object> sources
    ) throws ReflectiveOperationException {
        final List<Object> unique = new ArrayList<>();
        for (MeshPointRef ref : refs) {
            Object match = null;
            boolean ambiguous = false;
            for (Object context : contexts(pack)) {
                final Object mesh = call(context, "b", new Class<?>[0]);
                if (mesh == null) continue;
                final Object candidate = pointById(mesh, ref.id());
                if (candidate == null || containsSame(sources, candidate)) continue;
                if (match != null && match != candidate) {
                    ambiguous = true;
                    break;
                }
                match = candidate;
            }
            if (!ambiguous && match != null && !containsSame(unique, match)) unique.add(match);
        }
        return unique;
    }

    /** Same fail-closed rule as points, using the endpoint pair as the mesh-local edge key. */
    private static List<Object> uniqueCustomEdgeMatches(
        final Object pack,
        final List<MeshEdgeRef> refs,
        final Object source
    ) throws ReflectiveOperationException {
        final List<Object> unique = new ArrayList<>();
        for (MeshEdgeRef ref : refs) {
            Object match = null;
            boolean ambiguous = false;
            for (Object context : contexts(pack)) {
                final Object mesh = call(context, "b", new Class<?>[0]);
                if (mesh == null) continue;
                for (Object candidate : edges(mesh)) {
                    if (candidate == source || !edgeMatches(candidate, ref)) continue;
                    if (match != null && match != candidate) {
                        ambiguous = true;
                        break;
                    }
                    match = candidate;
                }
                if (ambiguous) break;
            }
            if (!ambiguous && match != null && !containsSame(unique, match)) unique.add(match);
        }
        return unique;
    }

    private static boolean edgeMatches(final Object candidate, final MeshEdgeRef ref)
        throws ReflectiveOperationException {
        final Object first = call(candidate, "getIndex1", new Class<?>[0]);
        final Object second = call(candidate, "getIndex2", new Class<?>[0]);
        if (!(first instanceof Number start) || !(second instanceof Number end)) return false;
        return ref.startPointId() == Math.min(start.intValue(), end.intValue())
            && ref.endPointId() == Math.max(start.intValue(), end.intValue());
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
        final Object scale = call(pack, "aL", new Class<?>[0]);
        if (!(scale instanceof Number number)) return null;
        return counterpartPoint(mirror, source, mesh, pack, context, number.floatValue());
    }

    private static Object counterpartPoint(
        final Object mirror,
        final Object source,
        final Object mesh,
        final Object pack,
        final Object context,
        final float tolerance
    ) throws ReflectiveOperationException {
        final Object position = call(source, "getPos", new Class<?>[0]);
        final Object target = mirrorPoint(context, mirror, position);
        if (target == null) return null;
        final Class<?> vector = target.getClass();

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
        return nearest != null && best < tolerance ? nearest : null;
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
        final Constructor<?> constructor = edgeConstructor(edge.getClass(), type);
        if (constructor == null) return null;
        final Object rebuilt = startId < endId
            ? constructor.newInstance(startId, endId, type)
            : constructor.newInstance(endId, startId, type);
        final Object edges = call(mesh, "getEdges", new Class<?>[0]);
        if (!(edges instanceof Collection<?> collection)) return null;
        for (Object candidate : collection) if (rebuilt.equals(candidate)) return candidate;
        return null;
    }

    private static Constructor<?> edgeConstructor(
        final Class<?> sourceType,
        final Object edgeType
    ) {
        final Class<?> edgeClass = edgeType == null ? null : edgeType.getClass().getEnclosingClass();
        for (Class<?> current = sourceType; current != null; current = current.getSuperclass()) {
            if (edgeClass != null && current == edgeClass) break;
            final Constructor<?> constructor = threeArgumentEdgeConstructor(current);
            if (constructor != null) return constructor;
        }
        return edgeClass == null ? null : threeArgumentEdgeConstructor(edgeClass);
    }

    private static Constructor<?> threeArgumentEdgeConstructor(final Class<?> type) {
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            final Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length != 3 || parameters[0] != int.class || parameters[1] != int.class) {
                continue;
            }
            try {
                candidate.setAccessible(true);
                return candidate;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
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
            final Object viewContext = activeMeshViewContext();
            return viewContext == null ? null : property(viewContext, "currentEditMode");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** The current mesh action pack ({@code Z}), which owns the native edit envelope. */
    static Object activeMeshActionPack() {
        try {
            final Object viewContext = activeMeshViewContext();
            if (viewContext == null) return null;
            return invoke(viewContext, "getLastActionPack");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Refuses a cached action pack unless it still owns the exact current mesh edit mode. */
    static boolean actionPackOwnsEditMode(final Object actionPack, final Object editMode) {
        if (actionPack == null || editMode == null) return false;
        try {
            return call(actionPack, "aP", new Class<?>[0]) == editMode;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    private static Object activeMeshViewContext() throws ReflectiveOperationException {
        final Object panel = CURRENT_PANEL.get();
        if (panel == null) return null;
        final Object toolMode = property(panel, "toolMode");
        final Object controller = invoke(toolMode, "getCtrl$cubism");
        final Object completePack = property(controller, "completePack");
        return property(completePack, "currentViewContext");
    }

    /** Starts one undoable step through the host's own mechanism; never invents an undo group. */
    static Object beginUndoGroup(final Object owner, final String label) {
        try {
            Method begin = declaredMethod(owner.getClass(), "a", String.class);
            if (begin == null) begin = declaredMethod(owner.getClass(), "beginEdit", String.class);
            return begin == null ? null : begin.invoke(owner, label);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Installs the host's canonical live-mesh SimpleUndo snapshots before direct mutations. */
    static void snapshotMeshesForUndo(final Object actionPack, final String label)
        throws ReflectiveOperationException {
        final Method snapshot = declaredMethod(actionPack.getClass(), "d", String.class);
        if (snapshot == null) throw new NoSuchMethodException("mesh snapshot helper");
        snapshot.invoke(actionPack, label);
    }

    /** Commits the current host edit with the same explicit arguments used by native actions. */
    static void commitUndoGroup(final Object owner) throws ReflectiveOperationException {
        endUndoGroup(owner, false, false);
    }

    /** Commits a direct edit, immediately reverts it, and removes that entry from host history. */
    static void cancelUndoGroup(final Object owner) throws ReflectiveOperationException {
        endUndoGroup(owner, false, true);
    }

    private static void endUndoGroup(
        final Object owner,
        final boolean cancelled,
        final boolean revert
    ) throws ReflectiveOperationException {
        final Method commit = findMethod(owner.getClass(), "a", boolean.class, boolean.class, null);
        if (commit != null) {
            commit.invoke(owner, cancelled, revert, null);
            return;
        }
        final Method end = findMethod(owner.getClass(), "endEdit", boolean.class, null);
        if (end == null) throw new NoSuchMethodException("mesh edit commit");
        final Object ended = end.invoke(owner, cancelled, null);
        if (!revert || !Boolean.TRUE.equals(ended)) return;
        final Object undoManager = call(owner, "getUndoManager", new Class<?>[0]);
        if (undoManager == null) throw new NoSuchMethodException("mesh undo manager");
        final Method undo = declaredMethod(undoManager.getClass(), "revert");
        if (undo == null) throw new NoSuchMethodException("mesh undo revert");
        undo.invoke(undoManager);
    }

    static boolean canAddPoint(final Object mesh) {
        return addPointMethod(mesh) != null;
    }

    static void addPoint(final Object mesh, final float x, final float y)
        throws ReflectiveOperationException {
        final Method method = addPointMethod(mesh);
        if (method == null) throw new NoSuchMethodException("mesh point addition");
        final Object normal = enumConstant(method.getParameterTypes()[2], "NORMAL");
        if (normal == null) throw new NoSuchFieldException("NORMAL point type");
        method.invoke(mesh, x, y, normal, -1L);
    }

    private static Method addPointMethod(final Object mesh) {
        for (Method method : mesh.getClass().getMethods()) {
            if (!method.getName().equals("addPoint") || method.getParameterCount() != 4) continue;
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] == float.class && parameters[1] == float.class
                && parameters[3] == long.class && enumConstant(parameters[2], "NORMAL") != null) {
                return method;
            }
        }
        return null;
    }

    static boolean canMovePoint(final Object point) throws ReflectiveOperationException {
        final Object current = call(point, "getPos", new Class<?>[0]);
        if (current == null) return false;
        return vectorConstructor(current.getClass()) != null
            && movePointMethod(point.getClass(), current.getClass()) != null;
    }

    static void movePoint(final Object point, final float x, final float y)
        throws ReflectiveOperationException {
        final Object current = call(point, "getPos", new Class<?>[0]);
        if (current == null) throw new NoSuchMethodException("mesh point position");
        final Constructor<?> vector = vectorConstructor(current.getClass());
        if (vector == null) throw new NoSuchMethodException("mesh point vector construction");
        final Method move = movePointMethod(point.getClass(), current.getClass());
        if (move == null) throw new NoSuchMethodException("mesh point movement");
        move.invoke(point, vector.newInstance(x, y), 1.0f);
    }

    private static Constructor<?> vectorConstructor(final Class<?> vectorType) {
        try {
            final Constructor<?> constructor = vectorType.getDeclaredConstructor(float.class, float.class);
            constructor.setAccessible(true);
            return constructor;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static Method movePointMethod(final Class<?> pointType, final Class<?> vectorType) {
        for (Class<?> current = pointType; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("moveToOnLocal") || method.getParameterCount() != 2) continue;
                final Class<?>[] parameters = method.getParameterTypes();
                if (!parameters[0].isAssignableFrom(vectorType) || parameters[1] != float.class) continue;
                try {
                    method.setAccessible(true);
                    return method;
                } catch (RuntimeException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static boolean canAddEdge(final Object mesh, final MeshEdgeRef edge) {
        return addEdgeMethod(mesh, edge) != null;
    }

    static void addEdge(final Object mesh, final MeshEdgeRef edge)
        throws ReflectiveOperationException {
        final Method method = addEdgeMethod(mesh, edge);
        if (method == null) throw new NoSuchMethodException("mesh edge addition");
        final Object type = edgeType(method, edge);
        final int before = edgeCount(mesh);
        method.invoke(mesh, edge.startPointId(), edge.endPointId(), type, false, false);
        if (edgeCount(mesh) != before + 1) throw new IllegalStateException("host added no edge");
    }

    private static Method addEdgeMethod(final Object mesh, final MeshEdgeRef edge) {
        if (edge.kind() == dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN) return null;
        for (Method method : mesh.getClass().getMethods()) {
            if (!method.getName().equals("addEdge") || method.getParameterCount() != 5) continue;
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] == int.class && parameters[1] == int.class
                && parameters[3] == boolean.class && parameters[4] == boolean.class
                && edgeType(method, edge) != null) return method;
        }
        return null;
    }

    private static Object edgeType(final Method method, final MeshEdgeRef edge) {
        return enumConstant(method.getParameterTypes()[2], switch (edge.kind()) {
            case BORDER -> "LOCKED";
            case INNER -> "NORMAL";
            case UNKNOWN -> "";
        });
    }

    private static int edgeCount(final Object mesh) throws ReflectiveOperationException {
        final Object value = call(mesh, "getEdges", new Class<?>[0]);
        return value instanceof Collection<?> edges ? edges.size() : -1;
    }

    private static Object enumConstant(final Class<?> type, final String name) {
        if (!type.isEnum()) return null;
        for (Object value : type.getEnumConstants()) {
            if (value instanceof Enum<?> item && item.name().equals(name)) return value;
        }
        return null;
    }

    private static Method findMethod(
        final Class<?> owner,
        final String name,
        final Class<?> first,
        final Class<?> second,
        final Class<?> third
    ) {
        for (Method method : owner.getMethods()) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals(name) || parameters.length != 3) continue;
            if (parameters[0] != first || parameters[1] != second) continue;
            if (third != null && parameters[2] != third) continue;
            return method;
        }
        return null;
    }

    private static Method findMethod(
        final Class<?> owner,
        final String name,
        final Class<?> first,
        final Class<?> second
    ) {
        for (Method method : owner.getMethods()) {
            final Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 2
                && parameters[0] == first && (second == null || parameters[1] == second)) return method;
        }
        return null;
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
                final Object type = call(edge, "getType", new Class<?>[0]);
                final dev.turboism.sdk.cubism.mesh.MeshEdgeKind kind = type instanceof Enum<?> value
                    ? switch (value.name()) {
                        case "LOCKED" -> dev.turboism.sdk.cubism.mesh.MeshEdgeKind.BORDER;
                        case "NORMAL" -> dev.turboism.sdk.cubism.mesh.MeshEdgeKind.INNER;
                        default -> dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN;
                    }
                    : dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN;
                edges.add(new MeshEdgeRef(start.intValue(), end.intValue(), kind));
            }
        }
    }

    static int countLiveEdges(final Object mesh, final List<MeshEdgeRef> refs)
        throws ReflectiveOperationException {
        final Object hostEdges = call(mesh, "getEdges", new Class<?>[0]);
        if (!(hostEdges instanceof Iterable<?> iterable)) return 0;
        int count = 0;
        for (Object candidate : iterable) {
            final Object first = call(candidate, "getIndex1", new Class<?>[0]);
            final Object second = call(candidate, "getIndex2", new Class<?>[0]);
            if (!(first instanceof Number start) || !(second instanceof Number end)) continue;
            final int low = Math.min(start.intValue(), end.intValue());
            final int high = Math.max(start.intValue(), end.intValue());
            for (MeshEdgeRef ref : refs) {
                if (ref.startPointId() == low && ref.endPointId() == high) {
                    count++;
                    break;
                }
            }
        }
        return count;
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
                if (ref.startPointId() == low && ref.endPointId() == high) {
                    if (!containsSame(live, candidate)) live.add(candidate);
                    break;
                }
            }
        }
        if (live.isEmpty()) return 0;
        final Object handler = call(mesh, "getHandler", new Class<?>[0]);
        if (handler == null) return 0;
        final Method remove = declaredMethod(handler.getClass(), "a", List.class);
        if (remove == null || !hasUndoAttachmentMethod(groupUndo)) return 0;
        final Object undo = remove.invoke(handler, live);
        if (undo == null) return 0;
        final Method plusAssign = declaredMethod(groupUndo.getClass(), "plusAssign", undo.getClass());
        if (plusAssign == null) throw new NoSuchMethodException("mesh edge undo attachment");
        plusAssign.invoke(groupUndo, undo);
        return live.size();
    }

    private static boolean hasUndoAttachmentMethod(final Object groupUndo) {
        for (Method method : groupUndo.getClass().getMethods()) {
            if (method.getName().equals("plusAssign") && method.getParameterCount() == 1) return true;
        }
        Class<?> type = groupUndo.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals("plusAssign") && method.getParameterCount() == 1) return true;
            }
            type = type.getSuperclass();
        }
        return false;
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

    static Iterable<?> edges(final Object mesh) throws ReflectiveOperationException {
        final Object value = call(mesh, "getEdges", new Class<?>[0]);
        return value instanceof Iterable<?> iterable ? iterable : List.of();
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

    private static List<MeshPointRef> customPointContributions(
        final MeshEditContribution aggregate
    ) {
        final List<MeshEditContribution> collected = COLLECTED_CONTRIBUTIONS.get();
        if (collected == null) return aggregate.points();
        final List<MeshEditContribution> defaults = DEFAULT_CONTRIBUTIONS.get();
        final List<MeshPointRef> custom = new ArrayList<>();
        for (MeshEditContribution contribution : collected) {
            if (!containsContributionIdentity(defaults, contribution)) {
                custom.addAll(contribution.points());
            }
        }
        return custom;
    }

    private static List<MeshEdgeRef> customEdgeContributions(
        final MeshEditContribution aggregate
    ) {
        final List<MeshEditContribution> collected = COLLECTED_CONTRIBUTIONS.get();
        if (collected == null) return aggregate.edges();
        final List<MeshEditContribution> defaults = DEFAULT_CONTRIBUTIONS.get();
        final List<MeshEdgeRef> custom = new ArrayList<>();
        for (MeshEditContribution contribution : collected) {
            if (!containsContributionIdentity(defaults, contribution)) {
                custom.addAll(contribution.edges());
            }
        }
        return custom;
    }

    private static boolean containsContributionIdentity(
        final List<MeshEditContribution> contributions,
        final MeshEditContribution candidate
    ) {
        if (contributions == null) return false;
        for (MeshEditContribution contribution : contributions) {
            if (contribution == candidate) return true;
        }
        return false;
    }

    static boolean containsIdentity(final Iterable<?> collected, final Object candidate) {
        for (Object existing : collected) if (existing == candidate) return true;
        return false;
    }

    private static boolean containsSame(final List<?> collected, final Object candidate) {
        for (Object existing : collected) if (existing == candidate) return true;
        return false;
    }

    static Object pointMesh(final Object point) throws ReflectiveOperationException {
        final Object mesh = call(point, "a", new Class<?>[0]);
        return mesh == null ? call(point, "getMesh", new Class<?>[0]) : mesh;
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
