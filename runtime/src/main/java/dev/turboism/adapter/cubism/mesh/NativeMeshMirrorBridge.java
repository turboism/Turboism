package dev.turboism.adapter.cubism.mesh;

import static dev.turboism.adapter.cubism.mesh.NativeMeshMirrorReflectionOps.*;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.cubism.mesh.MirrorAxisState;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    private static final Consumer<String> DEFAULT_DIAGNOSTIC = ignored -> {};
    private static final AtomicReference<Consumer<String>> DIAGNOSTIC = new AtomicReference<>(DEFAULT_DIAGNOSTIC);

    private NativeMeshMirrorBridge() {}

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
            FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never reach the host call site.
        }
    }

    /** Installs an enabled bridge using the supplied axis and UI services. */
    public static void install(final RuntimeMeshMirrorAxisService axis, final RuntimeMeshEditUiService ui) {
        install(axis, ui, true);
    }

    /** Installs a bridge using the supplied services and enabled-state policy. */
    public static void install(
            final RuntimeMeshMirrorAxisService axis, final RuntimeMeshEditUiService ui, final boolean enabled) {
        if (!INSTALLED.compareAndSet(null, new Binding(axis, ui, enabled, PARTICIPATION, COUNTERPARTS))) {
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
        NativeMeshMirrorCounterpartOps.resetMirrorOverride();
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

    // INSTALLED holds the live bridge; a different instance means the binding was revoked.
    /**
     * Attaches a widget the host built before binding. Idempotent and safe to call again:
     * the contribution may still be missing at bind time, in which case the recording is kept
     * and the installer's contribution observer calls back here once it arrives.
     */
    @SuppressWarnings("ReferenceEquality")
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
    public static Object adjustPoint(final Object original, final Object mirrorState, final Object source) {
        return adjust(original, mirrorState, source, false);
    }

    /** Returns the source point projected onto the resolved mirror axis when possible. */
    public static Object adjustAxisPoint(final Object original, final Object mirrorState, final Object source) {
        return adjust(original, mirrorState, source, true);
    }

    /** Widens the exact 5.2.03 native predicate only for plugin-registered host-neutral tools. */
    public static boolean adjustToolEligibility(final boolean original, final Object nativeTool) {
        if (original || INSTALLED.get() == null || nativeTool == null) return original;
        try {
            final MeshEditTool tool =
                    nativeTool instanceof Enum<?> value ? meshEditTool(value.name()) : MeshEditTool.UNKNOWN;
            return tool != MeshEditTool.UNKNOWN && TOOL_ELIGIBILITY.isExtended(tool);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
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
            final boolean original, final Object mirrorState, final Object source, final float threshold) {
        final Binding binding = INSTALLED.get();
        if (binding == null || !binding.enabled || binding.axis.currentAngleDegrees() == 0.0f || source == null)
            return original;
        try {
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
            return line == null
                    ? original
                    : MeshMirrorGeometry.hit(line, coordinate(source, "getX"), coordinate(source, "getY"), threshold);
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
            final Object color) {
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
                        (nativeSegment.start.y() + nativeSegment.end.y()) * 0.5f);
            }
            binding.axis.observeAxis(axisValue, vertical, pivot.x(), pivot.y());
            final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
            if (line == null) return false;
            final MeshMirrorGeometry.Segment segment =
                    MeshMirrorGeometry.covering(line, nativeSegment.firstCorner, nativeSegment.oppositeCorner);
            if (!invokeVoid(
                    nativeSegment.drawer,
                    "a",
                    vector(drawImpl, segment.start()),
                    vector(drawImpl, segment.end()),
                    color,
                    lineWidth,
                    0.0f)) {
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Handles the 5.0/5.1 inlined axis draw: those generations emit the mirror-axis
     * segment through the shared draw-line helper inside the guide pass instead of a
     * dedicated method, so the weave replaces that call. With a custom axis active the
     * resolved segment is redrawn through the same host helper; otherwise the original
     * call is re-issued verbatim so native rendering is unchanged.
     */
    public static void legacyAxisDraw(
            final Object drawer,
            final Object start,
            final Object end,
            final Object color,
            final float first,
            final float second,
            final int flags,
            final Object defaults) {
        final Binding binding = INSTALLED.get();
        try {
            if (binding != null
                    && binding.enabled
                    && binding.axis.currentAngleDegrees() != 0.0f
                    && drawer != null
                    && start != null
                    && end != null) {
                final float startX = coordinate(start, "getX");
                final float startY = coordinate(start, "getY");
                final float endX = coordinate(end, "getX");
                final float endY = coordinate(end, "getY");
                final boolean vertical = Math.abs(endX - startX) < 0.0001f;
                final float axisValue = vertical ? startX : startY;
                MeshMirrorGeometry.Point pivot = canvasCenter(CURRENT_PANEL.get());
                if (pivot == null) {
                    pivot = new MeshMirrorGeometry.Point((startX + endX) * 0.5f, (startY + endY) * 0.5f);
                }
                binding.axis.observeAxis(axisValue, vertical, pivot.x(), pivot.y());
                final MeshMirrorGeometry.Line line = binding.axis.resolveLine();
                if (line != null) {
                    MeshMirrorGeometry.Point firstCorner = new MeshMirrorGeometry.Point(startX, startY);
                    MeshMirrorGeometry.Point oppositeCorner = new MeshMirrorGeometry.Point(endX, endY);
                    try {
                        final Object panel = CURRENT_PANEL.get();
                        final Object controller = invoke(property(panel, "toolMode"), "getCtrl$cubism");
                        final Object pack = property(controller, "completePack");
                        final MeshMirrorGeometry.Point[] bounds = viewportBounds(
                                property(pack, "currentViewContext"),
                                pack,
                                drawer.getClass().getClassLoader());
                        firstCorner = bounds[0];
                        oppositeCorner = bounds[1];
                    } catch (ReflectiveOperationException | RuntimeException ignored) {
                        // Retain the native segment's extent if viewport access is unavailable.
                    }
                    final MeshMirrorGeometry.Segment segment =
                            MeshMirrorGeometry.covering(line, firstCorner, oppositeCorner);
                    final Object resolvedStart = vector(start, segment.start());
                    final Object resolvedEnd = vector(start, segment.end());
                    invokeLegacyAxisCall(drawer, resolvedStart, resolvedEnd, color, first, second, flags, defaults);
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            // The native segment is drawn below when the custom path is unavailable.
        }
        try {
            invokeLegacyAxisCall(drawer, start, end, color, first, second, flags, defaults);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            diagnostic("LEGACY_AXIS_DRAW_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Re-issues the intercepted host draw-line call with identical argument order. */
    private static void invokeLegacyAxisCall(
            final Object drawer,
            final Object start,
            final Object end,
            final Object color,
            final float first,
            final float second,
            final int flags,
            final Object defaults)
            throws ReflectiveOperationException {
        if (drawer == null) throw new NoSuchMethodException("drawer");
        for (final Method method : MethodHandleCache.declaredOverloads(drawer.getClass(), "a", 8)) {
            if (!Modifier.isStatic(method.getModifiers())) continue;
            try {
                if (!method.canAccess(null)) ReflectAccess.open(method);
                method.invoke(null, drawer, start, end, color, first, second, flags, defaults);
                return;
            } catch (IllegalArgumentException ignored) {
                // Try the next overload.
            }
        }
        throw new NoSuchMethodException(drawer.getClass().getName() + "#a");
    }

    private static Object adjust(
            final Object original, final Object mirrorState, final Object source, final boolean projection) {
        final Binding binding = INSTALLED.get();
        if (binding == null || !binding.enabled || binding.axis.currentAngleDegrees() == 0.0f || source == null)
            return original;
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

    static void repaintCanvas(final Object panel) {
        try {
            final Object controller = invoke(property(panel, "toolMode"), "getCtrl$cubism");
            invoke(property(controller, "completePack"), "repaintCanvas", true);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A missing host refresh surface must not break angle input.
        }
    }

    /** Clears cached host UI and context identities after a context transition. */
    public static void clearHostContext() {
        CURRENT_PANEL.set(null);
        CURRENT_CONTEXT.set(null);
        final Binding binding = INSTALLED.get();
        if (binding != null) binding.axis.clearPivot();
    }

    private static NativeSegment nativeSegment(final Object drawImpl, final float axisValue, final boolean vertical)
            throws ReflectiveOperationException {
        final Object viewContext = invoke(drawImpl, "a");
        final Object completePack = invoke(drawImpl, "b");
        final Object renderSystem = invoke(viewContext, "getRenderSystem");
        final Object drawer = invoke(renderSystem, "b");
        final Object sceneGraph = invoke(viewContext, "getSceneGraph");
        final Object objectsOnCanvas = invoke(sceneGraph, "getObjectsOnCanvas");
        final Object sortingLayer =
                staticField(drawImpl.getClass().getClassLoader(), "com.live2d.graphics3d.component.a.c$b", "a");
        if (!invokeVoid(drawer, "a", objectsOnCanvas)
                || !invokeVoid(drawer, "a", sortingLayer)
                || !invokeVoid(drawer, "a", 51)) return null;

        final MeshMirrorGeometry.Point[] bounds =
                viewportBounds(viewContext, completePack, drawImpl.getClass().getClassLoader());
        final MeshMirrorGeometry.Point first = bounds[0];
        final MeshMirrorGeometry.Point opposite = bounds[1];
        return new NativeSegment(
                drawer,
                vertical
                        ? new MeshMirrorGeometry.Point(axisValue, opposite.y())
                        : new MeshMirrorGeometry.Point(first.x(), axisValue),
                vertical
                        ? new MeshMirrorGeometry.Point(axisValue, first.y())
                        : new MeshMirrorGeometry.Point(opposite.x(), axisValue),
                first,
                opposite);
    }

    private static MeshMirrorGeometry.Point[] viewportBounds(
            final Object viewContext, final Object completePack, final ClassLoader loader)
            throws ReflectiveOperationException {
        final Object viewport = invoke(invoke(viewContext, "getViewArea"), "getViewAreaViewport");
        final Object panelRect = invoke(invoke(completePack, "getMainViewPanel"), "getRect");
        final Object camera = invoke(viewContext, "getCameraManager");
        final int viewX = number(invoke(viewport, "getX")).intValue();
        final int viewY = number(invoke(viewport, "getY")).intValue();
        final int viewW = number(invoke(viewport, "getW")).intValue();
        final int viewH = number(invoke(viewport, "getH")).intValue();
        final int panelH = number(invoke(panelRect, "getH")).intValue();
        final Object docTopLeft = invoke(camera, "componentToDocument", point(loader, viewX, -viewY));
        final Object docBottomRight =
                invoke(camera, "componentToDocument", point(loader, viewX + viewW, -viewY + viewH + panelH));
        return new MeshMirrorGeometry.Point[] {
            new MeshMirrorGeometry.Point(coordinate(docTopLeft, "getX"), coordinate(docTopLeft, "getY")),
            new MeshMirrorGeometry.Point(coordinate(docBottomRight, "getX"), coordinate(docBottomRight, "getY"))
        };
    }

    private static boolean observePanelPivot(final Binding binding, final Object panel) {
        try {
            final CanvasPivot canvasPivot = canvasPivot(panel);
            final Object context = new ContextIdentity(
                    canvasPivot.viewContext(),
                    canvasPivot.editMode(),
                    canvasPivot.model(),
                    canvasPivot.source(),
                    canvasPivot.canvas());
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
                number(invokeEither(canvas, "getPixelHeight", "getHeight")).floatValue() * 0.5f);
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

    private record NativeSegment(
            Object drawer,
            MeshMirrorGeometry.Point start,
            MeshMirrorGeometry.Point end,
            MeshMirrorGeometry.Point firstCorner,
            MeshMirrorGeometry.Point oppositeCorner) {}

    private record CanvasPivot(
            Object viewContext,
            Object editMode,
            Object model,
            Object source,
            Object canvas,
            float centerX,
            float centerY) {}

    private record ContextIdentity(Object viewContext, Object editMode, Object model, Object source, Object canvas) {
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

    private static final ThreadLocal<Object> EDGE_UNDO_GROUP = new ThreadLocal<>();
    /** Exact host edges selected by the default resolver, scoped to the current dispatch only. */
    private static final ThreadLocal<List<Object>> DEFAULT_COUNTERPART_EDGES = new ThreadLocal<>();

    private static final RuntimeMeshEditParticipation PARTICIPATION = new RuntimeMeshEditParticipation();
    private static final RuntimeMeshMirrorCounterparts COUNTERPARTS = new RuntimeMeshMirrorCounterparts();
    private static final RuntimeMeshMirrorToolEligibility TOOL_ELIGIBILITY = new RuntimeMeshMirrorToolEligibility();
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

    /**
     * Returns the per-plugin mirror-counterpart view that owns its own resolver override over
     * this session's shared resolution.
     */
    public static PluginMeshMirrorCounterparts pluginCounterparts() {
        return new PluginMeshMirrorCounterparts(counterparts());
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
            boolean endpointEdgeSources) {}

    record ProvenanceMark(int points, int edges, int defaults, int collected) {}

    private record DispatchState(
            LiveEdit liveEdit,
            List<Object> points,
            List<Object> edges,
            List<MeshEditContribution> defaults,
            List<MeshEditContribution> collected) {}

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
            final ThreadLocal<List<MeshEditContribution>> target, final MeshEditContribution contribution) {
        List<MeshEditContribution> contributions = target.get();
        if (contributions == null) {
            contributions = new ArrayList<>();
            target.set(contributions);
        }
        contributions.add(contribution);
    }

    private static void rememberIdentity(final ThreadLocal<List<Object>> target, final Object value) {
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
                size(COLLECTED_CONTRIBUTIONS.get()));
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
        if (values != null && values.size() > size)
            values.subList(size, values.size()).clear();
    }

    private static DispatchState pushDispatchState() {
        final DispatchState previous = new DispatchState(
                LIVE_EDIT.get(),
                DEFAULT_COUNTERPART_POINTS.get(),
                DEFAULT_COUNTERPART_EDGES.get(),
                DEFAULT_CONTRIBUTIONS.get(),
                COLLECTED_CONTRIBUTIONS.get());
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
        if (value == null) local.remove();
        else local.set(value);
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
            final Object mirror = NativeMeshMirrorCounterpartOps.hostMirror(pack);
            if (mirror == null || !NativeMeshMirrorCounterpartOps.hostMirrorEnabled(mirror, pack)) return;
            final Object scale = call(pack, "aL", NO_PARAMS);
            if (!(scale instanceof Number number)) return;
            final float tolerance = 20.0f * number.floatValue();
            int moved = 0;
            for (Object context : contexts(pack)) {
                final Object delta = call(context, "p", NO_PARAMS);
                final Object mesh = call(context, "b", NO_PARAMS);
                if (delta == null || mesh == null) continue;
                final Object selection = call(mesh, "getSelection", NO_PARAMS);
                final Object selector = call(selection, "getPointSelector", NO_PARAMS);
                if (!(selector instanceof Iterable<?> selected)) continue;

                final MeshFrameIndex meshIndex = new MeshFrameIndex(mesh);
                final List<Object> selectedRefs = new ArrayList<>();
                final List<Object> compatibleSources = new ArrayList<>();
                final Set<Integer> selectedIds = new HashSet<>();
                for (Object reference : selected) {
                    selectedRefs.add(reference);
                    final Object live = NativeMeshMirrorCounterpartOps.compatiblePoint(mesh, reference, meshIndex);
                    compatibleSources.add(live);
                    if (live != null) selectedIds.add(pointId(live));
                }
                for (int index = 0; index < selectedRefs.size(); index++) {
                    final Object reference = selectedRefs.get(index);
                    final Object source = compatibleSources.get(index);
                    if (source == null) continue;
                    final float weight = NativeMeshMirrorCounterpartOps.selectionWeight(selector, reference);
                    final Object counterpart = NativeMeshMirrorCounterpartOps.counterpartPoint(
                            mirror, source, mesh, pack, context, tolerance, meshIndex);
                    if (counterpart == null || selectedIds.contains(pointId(counterpart))) continue;
                    final Object sourcePosition = call(source, "getPos", NO_PARAMS);
                    if (sourcePosition == null) continue;
                    final Object target = call(sourcePosition, "plus", new Class<?>[] {delta.getClass()}, delta);
                    final Object mirroredTarget = NativeMeshMirrorCounterpartOps.mirrorPoint(context, mirror, target);
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
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("MOVE_PARTICIPATION_FAILED reason=" + failure.getClass().getName());
        }
    }

    static Object compatiblePoint(final Object mesh, final Object reference, final MeshFrameIndex index)
            throws ReflectiveOperationException {
        return NativeMeshMirrorCounterpartOps.compatiblePoint(mesh, reference, index);
    }

    /**
     * Point-tool route: native 5.3 deletes mirror points and every incident mirror edge.
     * The argument order mirrors the operands already on the stack at the host deletion call.
     */
    public static void mirrorDeletePointAction(final Object sources, final Object groupUndo, final Object pack) {
        mirrorDeletePoints(sources, groupUndo, pack, true);
    }

    /** Deletes mirror points for an edge-driven host deletion within the supplied undo group. */
    public static void mirrorDeletePoints(final Object sources, final Object groupUndo, final Object pack) {
        mirrorDeletePoints(sources, groupUndo, pack, false);
    }

    /** Eraser route: source points are candidate-mesh handles, resolved by id in each live mesh. */
    public static void mirrorDeleteEraserPoints(final Object sources, final Object pack, final Object groupUndo) {
        mirrorDeletePoints(sources, groupUndo, pack, true, true);
    }

    private static void mirrorDeletePoints(
            final Object sources, final Object groupUndo, final Object pack, final boolean removeIncidentEdges) {
        mirrorDeletePoints(sources, groupUndo, pack, removeIncidentEdges, false);
    }

    private static void mirrorDeletePoints(
            final Object sources,
            final Object groupUndo,
            final Object pack,
            final boolean removeIncidentEdges,
            final boolean pointSourcesById) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled || pack == null || sources == null || groupUndo == null) return;
            if (!binding.participation.hasParticipants()) {
                diagnostic("PARTICIPATION_SKIPPED reason=NO_PARTICIPANT");
                return;
            }
            final Object mirror = NativeMeshMirrorCounterpartOps.hostMirror(pack);
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
                        ? dispatch(binding, mirror, pack, sourcePoints, List.of(), sourceRefs, List.of(), true, false)
                        : dispatch(binding, mirror, pack, sourcePoints, List.of(), sourceRefs, List.of());
                if (contribution.isEmpty()) {
                    diagnostic("PARTICIPATION_EMPTY kind=POINTS");
                    return;
                }
                final NativeMeshMirrorDeletionOps.PointDeletionResult deleted =
                        NativeMeshMirrorDeletionOps.applyPointDeletions(
                                pack,
                                groupUndo,
                                contribution,
                                sourcePoints,
                                removeIncidentEdges,
                                DEFAULT_COUNTERPART_POINTS.get());
                diagnostic(
                        deleted.points() == 0
                                ? "PARTICIPATION_REJECTED kind=POINTS reason=NO_LIVE_MATCH"
                                : "PARTICIPATION_APPLIED kind=POINTS count=" + deleted.points() + " incidentEdges="
                                        + deleted.edges());
            } finally {
                restoreDispatchState(previous);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("PARTICIPATION_FAILED kind=POINTS reason="
                    + failure.getClass().getName());
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
            final List<MeshEdgeRef> edges)
            throws ReflectiveOperationException {
        return dispatch(binding, mirror, pack, sourcePoints, sourceEdges, points, edges, false, false);
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
            final boolean endpointEdgeSources)
            throws ReflectiveOperationException {
        final LiveEdit live = new LiveEdit(
                pack,
                mirror,
                List.copyOf(sourcePoints),
                List.copyOf(sourceEdges),
                pointSourcesById,
                endpointEdgeSources);
        final LiveEdit previous = LIVE_EDIT.get();
        LIVE_EDIT.set(live);
        try {
            final MirrorAxisState axis = new MirrorAxisState(
                    NativeMeshMirrorCounterpartOps.hostMirrorEnabled(mirror, pack), binding.axis.currentAngleDegrees());
            return binding.participation.collect(new MeshDeletion(points, edges, axis, MeshSnapshot.empty()));
        } finally {
            restoreThreadLocal(LIVE_EDIT, previous);
        }
    }

    /** Contributions are revalidated against the live mesh; a stale id is dropped, never guessed. */
    private static List<MeshPointRef> pointRefs(final Object pack, final List<Object> points)
            throws ReflectiveOperationException {
        final List<MeshPointRef> refs = new ArrayList<>();
        for (Object point : points) {
            final int id = pointId(point);
            if (id < 0) continue;
            final Object position = call(point, "getPos", NO_PARAMS);
            final Object x = position == null ? null : call(position, "getX", NO_PARAMS);
            final Object y = position == null ? null : call(position, "getY", NO_PARAMS);
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
    public static void mirrorDeleteEraserEdges(final Object sources, final Object pack, final Object groupUndo) {
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
            final Object mirror = NativeMeshMirrorCounterpartOps.hostMirror(pack);
            if (mirror == null) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_MIRROR");
                return;
            }
            if (!NativeMeshMirrorCounterpartOps.hostMirrorEnabled(mirror, pack)) {
                diagnostic("ERASER_DELETE_SKIPPED reason=MIRROR_DISABLED");
                return;
            }

            final List<Object> sourceEdges = new ArrayList<>();
            for (Object edge : rawSources) if (edge != null) sourceEdges.add(edge);
            if (sourceEdges.isEmpty()) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_SOURCES");
                return;
            }
            final List<MeshEdgeRef> sourceRefs = NativeMeshMirrorDeletionOps.edgeRefs(sourceEdges);
            if (sourceRefs.isEmpty()) {
                diagnostic("ERASER_DELETE_SKIPPED reason=NO_SOURCE_REFS");
                return;
            }

            final DispatchState previous = pushDispatchState();
            try {
                final MeshEditContribution contribution =
                        dispatch(binding, mirror, pack, List.of(), sourceEdges, List.of(), sourceRefs, false, true);
                if (contribution.isEmpty()) {
                    diagnostic("PARTICIPATION_EMPTY kind=ERASER_EDGES");
                    return;
                }
                final int removed = NativeMeshMirrorDeletionOps.applyEraserEdgeDeletions(
                        pack, mirror, groupUndo, contribution, sourceEdges);
                diagnostic(
                        removed == 0
                                ? "PARTICIPATION_REJECTED kind=ERASER_EDGES reason=NO_LIVE_MATCH"
                                : "PARTICIPATION_APPLIED kind=ERASER_EDGES count=" + removed);
            } finally {
                restoreDispatchState(previous);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("PARTICIPATION_FAILED kind=ERASER_EDGES reason="
                    + failure.getClass().getName());
        }
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
            final Object mirror = NativeMeshMirrorCounterpartOps.hostMirror(pack);
            if (mirror == null) return;

            final Object first = call(edge, "getIndex1", NO_PARAMS);
            final Object second = call(edge, "getIndex2", NO_PARAMS);
            if (!(first instanceof Number start) || !(second instanceof Number end)) return;
            if (start.intValue() == end.intValue()) return;
            final MeshEdgeRef source = new MeshEdgeRef(
                    start.intValue(), end.intValue(), dev.turboism.sdk.cubism.mesh.MeshEdgeKind.UNKNOWN);

            final DispatchState previous = pushDispatchState();
            try {
                final MeshEditContribution contribution =
                        dispatch(binding, mirror, pack, List.of(), List.of(edge), List.of(), List.of(source));
                if (contribution.isEmpty()) {
                    diagnostic("PARTICIPATION_EMPTY kind=EDGES");
                    return;
                }
                final int removed = NativeMeshMirrorDeletionOps.applyEdgeDeletions(
                        pack, groupUndo, contribution, edge, DEFAULT_COUNTERPART_EDGES.get());
                diagnostic(
                        removed == 0
                                ? "PARTICIPATION_REJECTED kind=EDGES reason=NO_LIVE_MATCH"
                                : "PARTICIPATION_APPLIED kind=EDGES count=" + removed);
            } finally {
                restoreDispatchState(previous);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("PARTICIPATION_FAILED kind=EDGES reason="
                    + failure.getClass().getName());
        }
    }

    static Object counterpartPoint(
            final Object mirror, final Object source, final Object mesh, final Object pack, final Object context)
            throws ReflectiveOperationException {
        return NativeMeshMirrorCounterpartOps.counterpartPoint(mirror, source, mesh, pack, context);
    }

    static Object counterpartPoint(
            final Object mirror,
            final Object source,
            final Object mesh,
            final Object pack,
            final Object context,
            final float tolerance,
            final MeshFrameIndex index)
            throws ReflectiveOperationException {
        return NativeMeshMirrorCounterpartOps.counterpartPoint(mirror, source, mesh, pack, context, tolerance, index);
    }

    static Object counterpartEdge(
            final Object mirror, final Object edge, final Object mesh, final Object pack, final Object context)
            throws ReflectiveOperationException {
        return NativeMeshMirrorCounterpartOps.counterpartEdge(mirror, edge, mesh, pack, context);
    }

    static Object counterpartEdge(
            final Object mirror,
            final Object edge,
            final Object mesh,
            final Object pack,
            final Object context,
            final float tolerance,
            final MeshFrameIndex index)
            throws ReflectiveOperationException {
        return NativeMeshMirrorCounterpartOps.counterpartEdge(mirror, edge, mesh, pack, context, tolerance, index);
    }

    static Object hostMirror(final Object pack) {
        return NativeMeshMirrorCounterpartOps.hostMirror(pack);
    }

    /**
     * The mesh edit mode behind the panel the host last handed us, or null when no mesh edit is
     * open. Navigated through the same property chain the pivot resolution already uses.
     */
    static Object activeMeshEditMode() {
        try {
            final Object viewContext = activeMeshViewContext();
            if (viewContext == null) {
                diagnostic("MESH_EDIT_MODE_UNAVAILABLE reason=NO_VIEW_CONTEXT panel=" + (CURRENT_PANEL.get() != null));
                return null;
            }
            final Object mode = property(viewContext, "currentEditMode");
            diagnostic("MESH_EDIT_MODE_OBSERVED class="
                    + (mode == null ? "null" : mode.getClass().getName()));
            return mode;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            diagnostic("MESH_EDIT_MODE_UNAVAILABLE reason=" + failure.getClass().getName());
            return null;
        }
    }

    /** The current mesh action pack, which owns the native edit envelope. */
    static Object activeMeshActionPack() {
        try {
            final Object viewContext = activeMeshViewContext();
            if (viewContext == null) return null;
            final Object cached = invoke(viewContext, "getLastActionPack");
            final Object mode = property(viewContext, "currentEditMode");
            if (actionPackOwnsEditMode(cached, mode)) return cached;
            final Object refreshed = PopupMeshUndoContract.refresh(viewContext, cached);
            diagnostic("MESH_EDIT_ACTION_PACK cached="
                    + (cached == null ? "null" : cached.getClass().getName())
                    + " refreshed="
                    + (refreshed == null ? "null" : refreshed.getClass().getName())
                    + " ownsMode=" + actionPackOwnsEditMode(refreshed, mode));
            return refreshed;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    /** Refuses a cached action pack unless it still owns the exact current mesh edit mode. */
    static boolean actionPackOwnsEditMode(final Object actionPack, final Object editMode) {
        if (actionPack == null || editMode == null) return false;
        try {
            if (PopupMeshUndoContract.admitted(actionPack)) {
                return PopupMeshUndoContract.owns(actionPack, editMode);
            }
            return call(actionPack, "aP", NO_PARAMS) == editMode;
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
    static void snapshotMeshesForUndo(final Object actionPack, final String label, final Object group)
            throws ReflectiveOperationException {
        if (PopupMeshUndoContract.admitted(actionPack)) {
            PopupMeshUndoContract.snapshot(actionPack, label, group);
            return;
        }
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

    private static void endUndoGroup(final Object owner, final boolean cancelled, final boolean revert)
            throws ReflectiveOperationException {
        final Method commit = findMethod(owner.getClass(), "a", boolean.class, boolean.class, null);
        if (commit != null) {
            commit.invoke(owner, cancelled, revert, null);
            return;
        }
        final Method end = findMethod(owner.getClass(), "endEdit", boolean.class, null);
        if (end == null) throw new NoSuchMethodException("mesh edit commit");
        final Object ended = end.invoke(owner, cancelled, null);
        if (!revert || !Boolean.TRUE.equals(ended)) return;
        final Object undoManager = call(owner, "getUndoManager", NO_PARAMS);
        if (undoManager == null) throw new NoSuchMethodException("mesh undo manager");
        final Method undo = declaredMethod(undoManager.getClass(), "revert");
        if (undo == null) throw new NoSuchMethodException("mesh undo revert");
        undo.invoke(undoManager);
    }

    static boolean canAddPoint(final Object mesh) {
        return NativeMeshMirrorGeometryOps.canAddPoint(mesh);
    }

    static int addPoint(final Object mesh, final float x, final float y) throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.addPoint(mesh, x, y);
    }

    static List<Integer> addClosedLoopPointsAndEdges(
            final Object editMode,
            final Object editableMesh,
            final Object completePack,
            final String label,
            final float[] pointsXY)
            throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.addClosedLoopPointsAndEdges(
                editMode, editableMesh, completePack, label, pointsXY);
    }

    static List<Integer> addGeometricPointsAndEdges(
            final Object editMode,
            final Object editableMesh,
            final Object completePack,
            final String label,
            final float[] pointsXY,
            final int[] edgePairs)
            throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.addGeometricPointsAndEdges(
                editMode, editableMesh, completePack, label, pointsXY, edgePairs);
    }

    static void disarmNativePointAddPreview(final ClassLoader loader) {
        NativeMeshMirrorGeometryOps.disarmNativePointAddPreview(loader);
    }

    static void markSelectionBoundingBoxDirty(final Object editMode, final Object completePack) {
        NativeMeshMirrorGeometryOps.markSelectionBoundingBoxDirty(editMode, completePack);
    }

    static boolean canMovePoint(final Object point) throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.canMovePoint(point);
    }

    static void movePoint(final Object point, final float x, final float y) throws ReflectiveOperationException {
        NativeMeshMirrorGeometryOps.movePoint(point, x, y);
    }

    static boolean canAddEdge(final Object mesh, final MeshEdgeRef edge) {
        return NativeMeshMirrorGeometryOps.canAddEdge(mesh, edge);
    }

    static void addEdge(final Object mesh, final MeshEdgeRef edge) throws ReflectiveOperationException {
        NativeMeshMirrorGeometryOps.addEdge(mesh, edge);
    }

    static void collectSnapshot(final Object mesh, final List<MeshPointRef> points, final List<MeshEdgeRef> edges)
            throws ReflectiveOperationException {
        NativeMeshMirrorGeometryOps.collectSnapshot(mesh, points, edges);
    }

    static int countLiveEdges(final Object mesh, final List<MeshEdgeRef> refs) throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.countLiveEdges(mesh, refs);
    }

    static int removeEdgesInto(final Object mesh, final List<MeshEdgeRef> refs, final Object groupUndo)
            throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.removeEdgesInto(mesh, refs, groupUndo);
    }

    static void mirrorForTesting(final Object mirror) {
        NativeMeshMirrorCounterpartOps.mirrorForTesting(mirror);
    }

    /** Uses the host's own enable predicate; we never invent one. */
    static boolean hostMirrorEnabled(final Object mirror, final Object pack) {
        return NativeMeshMirrorCounterpartOps.hostMirrorEnabled(mirror, pack);
    }

    static List<?> contexts(final Object pack) throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.contexts(pack);
    }

    static List<?> points(final Object mesh) throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.points(mesh);
    }

    static Iterable<?> edges(final Object mesh) throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.edges(mesh);
    }

    static Object pointById(final Object mesh, final int id) throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.pointById(mesh, id);
    }

    static int pointId(final Object point) throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.pointId(point);
    }

    /** Plugin point proposals: everything collected that the default resolver did not produce. */
    static List<MeshPointRef> customPointContributions(final MeshEditContribution aggregate) {
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

    /** Plugin edge proposals: everything collected that the default resolver did not produce. */
    static List<MeshEdgeRef> customEdgeContributions(final MeshEditContribution aggregate) {
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

    // Identity scan by contract: duplicate detection is on the contribution instance itself.
    @SuppressWarnings("ReferenceEquality")
    private static boolean containsContributionIdentity(
            final List<MeshEditContribution> contributions, final MeshEditContribution candidate) {
        if (contributions == null) return false;
        for (MeshEditContribution contribution : contributions) {
            if (contribution == candidate) return true;
        }
        return false;
    }

    static boolean containsIdentity(final Iterable<?> collected, final Object candidate) {
        return NativeMeshMirrorReflectionOps.containsIdentity(collected, candidate);
    }

    static Object pointMesh(final Object point) throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.pointMesh(point);
    }

    /** Strict lookup by exact parameter types; see {@link NativeMeshMirrorReflectionOps}. */
    static Method declaredMethod(final Class<?> owner, final String name, final Class<?>... parameters) {
        return NativeMeshMirrorReflectionOps.declaredMethod(owner, name, parameters);
    }

    static Object call(final Object target, final String name, final Class<?>[] parameters, final Object... arguments)
            throws ReflectiveOperationException {
        return NativeMeshMirrorReflectionOps.call(target, name, parameters, arguments);
    }

    private record Binding(
            RuntimeMeshMirrorAxisService axis,
            RuntimeMeshEditUiService ui,
            boolean enabled,
            RuntimeMeshEditParticipation participation,
            RuntimeMeshMirrorCounterparts counterparts) {}

    /** Exact references delivered by the accepted public callback; never resolved by search. */
    private record PendingAttach(Object panel, Object widget) {}
}
