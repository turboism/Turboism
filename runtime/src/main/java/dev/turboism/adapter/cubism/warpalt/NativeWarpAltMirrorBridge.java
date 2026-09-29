package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final String WARP_BINDER_CLASS = "com.live2d.cubism.view.context.temporaryHandler.warp.WarpBinder";
    private static final String WARP_POINT_REF_CLASS = "com.live2d.cubism.doc.model.deformer.warp.WarpPointRef";

    private static final AtomicReference<Binding> INSTALLED = new AtomicReference<>();
    /** Armed mirror axis published by the plugin: 0=off, 1=vertical, 2=horizontal. */
    private static final java.util.concurrent.atomic.AtomicInteger ARMED_AXIS =
            new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * The reviewed warp binder type. Package-private mutable only so tests can point
     * the recognition at stub classes; production never changes it.
     */
    private static final AtomicReference<String> BINDER_CLASS_NAME = new AtomicReference<>(WARP_BINDER_CLASS);
    /** Same seam for the WarpPointRef recognition used by the weight mirror. */
    private static final AtomicReference<String> WARP_REF_CLASS_NAME = new AtomicReference<>(WARP_POINT_REF_CLASS);

    /** Reentrancy guard: the recursive counterpart write must not mirror again. */
    private static final ThreadLocal<Boolean> WEIGHT_MIRRORING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicBoolean WEIGHT_APPLIED_REPORTED = new AtomicBoolean();
    private static final AtomicLong WEIGHT_MIRROR_COUNT = new AtomicLong();
    private static final AtomicInteger WEIGHT_LAST_SOURCE = new AtomicInteger(-1);
    private static final AtomicInteger WEIGHT_LAST_COUNTERPART = new AtomicInteger(-1);
    /** Per-host-class caches of the reviewed counterpart-ref constructors; a miss is permanent per class. */
    private static final ConcurrentHashMap<Class<?>, Optional<Constructor<?>>> BASE_REF_CTORS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Constructor<?>>> WRAP_REF_CTORS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Optional<Field>> WRAP_TRANSFORM_FIELDS =
            new ConcurrentHashMap<>();

    private static final AtomicBoolean MOVE_APPLIED_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean GREEN_APPLIED_REPORTED = new AtomicBoolean();
    private static final AtomicLong LAST_THROTTLE = new AtomicLong();
    private static final java.util.Set<String> REPORTED_SKIPS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private static final Consumer<String> DEFAULT_DIAGNOSTIC = ignored -> {};
    private static final AtomicReference<Consumer<String>> DIAGNOSTIC = new AtomicReference<>(DEFAULT_DIAGNOSTIC);
    private static final RuntimeWarpAltMirrorParticipation PARTICIPATION = new RuntimeWarpAltMirrorParticipation();

    private record Binding(boolean enabled) {}

    private NativeWarpAltMirrorBridge() {}

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
        GREEN_APPLIED_REPORTED.set(false);
        WEIGHT_MIRRORING.remove();
        WEIGHT_APPLIED_REPORTED.set(false);
        WEIGHT_MIRROR_COUNT.set(0);
        WEIGHT_LAST_SOURCE.set(-1);
        WEIGHT_LAST_COUNTERPART.set(-1);
        WARP_REF_CLASS_NAME.set(WARP_POINT_REF_CLASS);
        BASE_REF_CTORS.clear();
        WRAP_REF_CTORS.clear();
        WRAP_TRANSFORM_FIELDS.clear();
    }

    /** Test seam: redirects binder recognition to a stub class name. */
    static void setBinderClassNameForTesting(final String name) {
        BINDER_CLASS_NAME.set(name);
    }

    /** Test seam: restores the reviewed binder class name. */
    static void resetBinderClassName() {
        BINDER_CLASS_NAME.set(WARP_BINDER_CLASS);
    }

    /** Test seam: redirects WarpPointRef recognition to a stub class name. */
    static void setWarpRefClassNameForTesting(final String name) {
        WARP_REF_CLASS_NAME.set(name);
    }

    /** @return how many mirrored weight writes this bridge applied this session. */
    static int weightMirrorAppliedCount() {
        return (int) WEIGHT_MIRROR_COUNT.get();
    }

    /** @return the point index of the last mirrored weight write's source, or -1. */
    static int weightMirrorLastSourceIndex() {
        return WEIGHT_LAST_SOURCE.get();
    }

    /** @return the point index of the last mirrored weight write's counterpart, or -1. */
    static int weightMirrorLastCounterpartIndex() {
        return WEIGHT_LAST_COUNTERPART.get();
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
            final long now = System.currentTimeMillis();
            if (now - LAST_THROTTLE.get() >= 1_000L) {
                LAST_THROTTLE.set(now);
                diagnostic("POINT_MOVE_CALL ref="
                        + (ref == null ? "null" : ref.getClass().getName()));
            }
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
            // Native Ctrl semantics hold here too: the gesture suppresses the
            // content-deformation application, so the mirrored counterpart write
            // moves the cage point without affecting child shapes — symmetric
            // self-only adjustment.
            final boolean vertical = axis == 1;
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
            // User-facing semantics (r32 feedback): 垂直镜像 moves the counterpart
            // vertically (up/down, y negated); 水平镜像 moves it horizontally
            // (left/right, x negated).
            final int counterpartRow = vertical ? height - 1 - row : row;
            final int counterpartColumn = vertical ? column : step - 1 - column;
            final int counterpart = counterpartRow * step + counterpartColumn;
            if (counterpart == index || counterpart * 2 + 1 >= positions.length || index * 2 + 1 >= positions.length) {
                return;
            }
            final float targetX = invokeFloat(target, "getX");
            final float targetY = invokeFloat(target, "getY");
            final float dx = targetX - positions[index * 2];
            final float dy = targetY - positions[index * 2 + 1];
            if (Math.abs(dx) <= AltAxisMirrorMath.MOVE_EPSILON && Math.abs(dy) <= AltAxisMirrorMath.MOVE_EPSILON) {
                return;
            }
            if (vertical) {
                positions[counterpart * 2] += weight * dx;
                positions[counterpart * 2 + 1] -= weight * dy;
            } else {
                positions[counterpart * 2] -= weight * dx;
                positions[counterpart * 2 + 1] += weight * dy;
            }
            if (MOVE_APPLIED_REPORTED.compareAndSet(false, true)) {
                diagnostic("MIRROR_APPLIED axis=" + (vertical ? "vertical" : "horizontal") + " step=" + step);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("POINT_MOVE_MIRROR_FAILED reason=" + failure.getClass().getName());
        }
    }

    /**
     * Weighted-add entry injected at the head of
     * {@code PointSelector.add(IPointRef, float, boolean)} — the converged write
     * of the Brush Selection Tool and every other weighted-selection flow. While
     * an axis is armed, the same weight is mirrored onto the axis counterpart
     * point of the same Warp deformer, inside the caller's selection/undo
     * envelope.
     *
     * @param selector the {@code PointSelector} instance being written
     * @param ref the point reference the caller is weighting
     * @param weight the incoming brush weight
     * @param reorder the caller's reorder flag (re-selects to the list tail)
     */
    public static void mirrorWeightAdd(
            final Object selector, final Object ref, final float weight, final boolean reorder) {
        mirrorWeightWrite(selector, ref, weight, reorder, false);
    }

    /**
     * Weight-set entry injected at the head of
     * {@code PointSelector.setWeight(IPointRef, float)}. Unlike {@code add} this
     * path only touches the weight map, so the mirrored write does the same.
     */
    public static void mirrorWeightSet(final Object selector, final Object ref, final float weight) {
        mirrorWeightWrite(selector, ref, weight, false, true);
    }

    /**
     * Shared weight-mirror core. Acts only when installed+enabled, a plugin
     * participates, an axis is armed, and the ref is a reviewed
     * {@code WarpPointRef} (including its transform-carrying subtype). The
     * counterpart is recomputed through the same armed-axis mapping as
     * {@link #mirrorPointMove} — 垂直镜像 flips the row, 水平镜像 flips the
     * column — then the identical native selector operation runs recursively
     * under a thread-local reentrancy guard, so selection-list, weight-map and
     * undo semantics stay exactly the host's own.
     */
    private static void mirrorWeightWrite(
            final Object selector,
            final Object ref,
            final float weight,
            final boolean reorder,
            final boolean setOnly) {
        try {
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled() || selector == null || ref == null) {
                return;
            }
            if (WEIGHT_MIRRORING.get()) {
                // The recursive counterpart write below re-enters this exact
                // instrumented method; guard first so it cannot mirror again.
                return;
            }
            if (!PARTICIPATION.hasParticipants()) {
                return;
            }
            final int axis = ARMED_AXIS.get();
            if (axis == 0) {
                return;
            }
            final ClassLoader loader = ref.getClass().getClassLoader();
            final Class<?> warpRefType = Class.forName(WARP_REF_CLASS_NAME.get(), false, loader);
            if (!warpRefType.isInstance(ref)) {
                // ArtMesh and other weighted selections are a different mirror domain.
                return;
            }
            final int index = invokeInt(ref, "a");
            if (index < 0) {
                return;
            }
            final Object source = invoke(ref, "d", new Class<?>[0]);
            final Object keyForm = invoke(ref, "f", new Class<?>[0]);
            if (source == null || keyForm == null) {
                weightSkip("NO_SOURCE_OR_FORM");
                return;
            }
            final int width = invokeInt(source, "getCol") + 1;
            final int height = invokeInt(source, "getRow") + 1;
            if (width <= 1 || height <= 1) {
                weightSkip("BAD_DIMS");
                return;
            }
            final Object gridArray = invoke(ref, "g", new Class<?>[0]);
            if (!(gridArray instanceof float[] positions) || positions.length != 2L * width * height) {
                weightSkip("DIM_MISMATCH");
                return;
            }
            if (index >= width * height) {
                weightSkip("INDEX_RANGE index=" + index);
                return;
            }
            final int row = index / width;
            final int column = index % width;
            final int counterpartRow = axis == 1 ? height - 1 - row : row;
            final int counterpartColumn = axis == 2 ? width - 1 - column : column;
            final int counterpartIndex = counterpartRow * width + counterpartColumn;
            if (counterpartIndex == index) {
                return;
            }
            final Object counterRef = counterpartRef(ref, warpRefType, source, counterpartIndex, keyForm);
            if (counterRef == null) {
                weightSkip("NO_COUNTERPART_REF class=" + ref.getClass().getName());
                return;
            }
            final Class<?> selectorType = selector.getClass();
            // The IPointRef parameter type is resolved from the selector's own
            // getCompatible signature — no compiled or name-based dependency on
            // the host selection interface.
            final Method getCompatible =
                    MethodHandleCache.declaredByArity(selectorType, "getCompatible", 1);
            final Class<?> pointRefType = getCompatible.getParameterTypes()[0];
            // Reuse the stored equal instance when the counterpart is already
            // selected — exactly what the brush itself does via getCompatible —
            // so no foreign ref identity enters the selection lists.
            final Object compatible = getCompatible.invoke(selector, counterRef);
            WEIGHT_MIRRORING.set(true);
            try {
                if (setOnly) {
                    MethodHandleCache.method(selectorType, "setWeight", pointRefType, float.class)
                            .invoke(selector, compatible != null ? compatible : counterRef, weight);
                } else {
                    // When the counterpart is already selected, the remove+add
                    // reorder keeps the list deduplicated for either flag; for a
                    // fresh insert forward the caller's flag verbatim.
                    final Object target = compatible != null ? compatible : counterRef;
                    MethodHandleCache.method(selectorType, "add", pointRefType, float.class, boolean.class)
                            .invoke(selector, target, weight, compatible != null || reorder);
                }
            } finally {
                WEIGHT_MIRRORING.remove();
            }
            WEIGHT_MIRROR_COUNT.incrementAndGet();
            WEIGHT_LAST_SOURCE.set(index);
            WEIGHT_LAST_COUNTERPART.set(counterpartIndex);
            if (WEIGHT_APPLIED_REPORTED.compareAndSet(false, true)) {
                diagnostic("WEIGHT_MIRROR_APPLIED axis=" + axis + " index=" + index + " counterpart=" + counterpartIndex);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("WEIGHT_MIRROR_FAILED reason=" + failure.getClass().getName());
        }
    }

    /**
     * Builds the counterpart point reference for the mirrored write. A base
     * {@code WarpPointRef} comes straight from the reviewed three-argument
     * constructor (positions/step are derived from the key form); the
     * transform-carrying subtype is rebuilt through its two-argument wrapper
     * constructor so the stored ref keeps its canvas transform. Any deviation
     * from the reviewed shapes fails closed by returning null.
     */
    private static Object counterpartRef(
            final Object ref,
            final Class<?> warpRefType,
            final Object source,
            final int counterpartIndex,
            final Object keyForm)
            throws ReflectiveOperationException {
        final Constructor<?> baseCtor = BASE_REF_CTORS
                .computeIfAbsent(warpRefType, type -> findCtor(type, source, keyForm))
                .orElse(null);
        if (baseCtor == null) {
            return null;
        }
        final Object inner = baseCtor.newInstance(source, counterpartIndex, keyForm);
        if (ref.getClass() == warpRefType) {
            return inner;
        }
        final Class<?> refClass = ref.getClass();
        final Constructor<?> wrap = WRAP_REF_CTORS
                .computeIfAbsent(refClass, type -> findWrapCtor(type, warpRefType))
                .orElse(null);
        if (wrap == null) {
            return null;
        }
        final Field transformField = WRAP_TRANSFORM_FIELDS
                .computeIfAbsent(refClass, type -> findFieldOfType(type, wrap.getParameterTypes()[1]))
                .orElse(null);
        if (transformField == null) {
            return null;
        }
        return wrap.newInstance(inner, transformField.get(ref));
    }

    /** Finds the reviewed {@code (source, int, form)} constructor on the WarpPointRef type. */
    private static Optional<Constructor<?>> findCtor(
            final Class<?> warpRefType, final Object source, final Object keyForm) {
        for (final Constructor<?> ctor : warpRefType.getConstructors()) {
            final Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 3
                    && params[1] == int.class
                    && params[0].isInstance(source)
                    && params[2].isInstance(keyForm)) {
                return Optional.of(ctor);
            }
        }
        return Optional.empty();
    }

    /** Finds the reviewed {@code (WarpPointRef, transform)} wrapper constructor on a subtype. */
    private static Optional<Constructor<?>> findWrapCtor(final Class<?> refClass, final Class<?> warpRefType) {
        for (final Constructor<?> ctor : refClass.getDeclaredConstructors()) {
            final Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && params[0].isAssignableFrom(warpRefType)) {
                return Optional.of(ctor);
            }
        }
        return Optional.empty();
    }

    /** Finds the declared field carrying the subtype's transform payload. */
    private static Optional<Field> findFieldOfType(final Class<?> refClass, final Class<?> fieldType) {
        for (Class<?> type = refClass; type != null; type = type.getSuperclass()) {
            for (final Field field : type.getDeclaredFields()) {
                if (fieldType.isAssignableFrom(field.getType()) && field.trySetAccessible()) {
                    return Optional.of(field);
                }
            }
        }
        return Optional.empty();
    }

    private static void weightSkip(final String reason) {
        if (REPORTED_SKIPS.add("weight:" + reason)) {
            diagnostic("WEIGHT_SKIP reason=" + reason);
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
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("TICK_FAILED reason=" + failure.getClass().getName());
        }
    }

    /**
     * Moves the axis counterpart of the selected grid point by the mirrored tick
     * displacement, in place, before the native move commits the dragged point.
     */
    private static void mirrorCounterpart(
            final Object binder, final int selectedIndex, final Object pos, final boolean shift)
            throws ReflectiveOperationException {
        final int width = invokeInt(binder, "rowPointSize");
        final int height = invokeInt(binder, "columnPointSize");
        if (width <= 0 || height <= 0) {
            return;
        }
        final Object indexPair = invoke(binder, "toGridIndex", new Class<?>[] {int.class}, selectedIndex);
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
        if (!(rows.get(counterpartRow) instanceof List<?> counterpartRowList) || counterpartRowList.size() != width) {
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
        if (Math.abs(dx) <= AltAxisMirrorMath.MOVE_EPSILON && Math.abs(dy) <= AltAxisMirrorMath.MOVE_EPSILON) {
            return;
        }
        final float counterpartX = invokeFloat(counterpart, "getX");
        final float counterpartY = invokeFloat(counterpart, "getY");
        final float targetX = shift ? counterpartX + dx : counterpartX - dx;
        final float targetY = shift ? counterpartY - dy : counterpartY + dy;
        invoke(counterpart, "setX", new Class<?>[] {float.class}, targetX);
        invoke(counterpart, "setY", new Class<?>[] {float.class}, targetY);
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
            final Object target, final String name, final Class<?>[] parameterTypes, final Object... args)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameterTypes);
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException accessFailure) {
            // Host event/action classes are package-private; the method itself is
            // public but the declaring class is not accessible without override.
            method.setAccessible(true);
            return method.invoke(target, args);
        }
    }

    private static boolean invokeBoolean(final Object target, final String name) throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Boolean enabled && enabled;
    }

    private static int invokeInt(final Object target, final String name) throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Number number ? number.intValue() : -1;
    }

    private static float invokeFloat(final Object target, final String name) throws ReflectiveOperationException {
        final Object value = invoke(target, name, new Class<?>[0]);
        return value instanceof Number number ? number.floatValue() : Float.NaN;
    }

    private static Integer invokeInteger(final Object target, final String name) throws ReflectiveOperationException {
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

    /** Route diagnostics: reports the drag dispatch once, then throttled ticks. */
    public static void diagDragDispatch(final Object event) {
        if (REPORTED_SKIPS.add("route:dragDispatch")) {
            diagEventModifiers(event, "DRAG_DISPATCH");
        }
        diagEventModifiersThrottled(event, "DRAG_DISPATCH_TICK");
    }

    /** Route diagnostics: reports the selected-point move entry. */
    public static void diagMoveSelected(final Object event) {
        diagEventModifiers(event, "MOVE_SELECTED");
    }

    /**
     * Injected at the head of the strip's mount routine with the strip instance;
     * hands it to the tool-strip registry so contributed buttons mount through
     * the strip's own R() mounting loop.
     */
    public static void mountViewContextMenu(final Object strip) {
        try {
            RuntimeViewContextMenuRegistry.getInstance().mount(strip);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("STRIP_MOUNT_FAILED reason=" + failure.getClass().getName());
        }
    }

    /**
     * Injected at the tail of the strip's layout dispatch (a(N, GEntity) RETURN);
     * records where the native flow layout seated each contributed button. The
     * button is a member of the strip's H list, so the same pass already assigns
     * its bounds — this hook observes only, it never writes coordinates.
     * Missing from the bridge until now — the injected call must resolve or
     * every strip dispatch throws NoSuchMethodError.
     */
    public static void positionStripButton(final Object strip) {
        try {
            RuntimeViewContextMenuRegistry.getInstance().positionButton(strip);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("STRIP_POSITION_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Strip (view context menu) diagnostics, prefixed for log filtering. */
    public static void diagStrip(final String stage) {
        try {
            diagnostic("STRIP_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // never reach the host call site
        }
    }

    /**
     * Diagnostic entry injected at the head of {@code CEActionManager.mouseAction(N)}:
     * reports whether gesture events reach the action pipeline at all, and which
     * action object owns them — used to trace where bezier-handle drags die.
     */
    public static void diagMouseAction(final Object manager, final Object event) {
        try {
            if (!REPORTED_SKIPS.add("mouse:" + (event == null ? "null" : event.getClass().getSimpleName()))) {
                return;
            }
            final Object current = invoke(manager, "getCurAction", new Class<?>[0]);
            diagnostic("MOUSE_ACTION event=" + (event == null ? "null" : event.getClass().getName())
                    + " av=" + safeBool(event, "av") + " aw=" + safeBool(event, "aw")
                    + " cur=" + (current == null ? "null" : current.getClass().getName()));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static final AtomicReference<String> LAST_INPUT_KIND = new AtomicReference<>("");
    private static final java.util.concurrent.atomic.AtomicInteger INPUT_KIND_RUN =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Diagnostic entry injected at the head of {@code CEViewContext.onInputEvent_exe(h)}:
     * the single ingress for every canvas input event — proves which event kinds
     * actually reach the view-context dispatch during a bezier-handle drag.
     * Consecutive events of the same kind are coalesced into a run count.
     */
    public static void diagInputEvent(final Object viewContext, final Object event) {
        try {
            // Zero-reflection first: prove the ingress ran even if kind lookup fails.
            final String eventClass = event == null ? "null" : event.getClass().getName();
            final String ctxClass = viewContext == null ? "null" : viewContext.getClass().getName();
            String kindName;
            try {
                final Object kind = invoke(event, "e", new Class<?>[0]);
                kindName = kind instanceof Enum<?> e ? e.name() : String.valueOf(kind);
            } catch (Throwable kindFailure) {
                FatalErrors.rethrowIfFatal(kindFailure);
                kindName = "kind-fail:" + kindFailure.getClass().getSimpleName();
            }
            if (kindName.equals(LAST_INPUT_KIND.get()) && !kindName.startsWith("kind-fail")) {
                INPUT_KIND_RUN.incrementAndGet();
                return;
            }
            final int run = INPUT_KIND_RUN.getAndSet(0);
            if (run > 0) {
                diagnostic("INPUT_EVENT_RUN x" + run);
            }
            LAST_INPUT_KIND.set(kindName);
            diagnostic("INPUT_EVENT kind=" + kindName + " event=" + eventClass + " ctx=" + ctxClass);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    /**
     * Diagnostic entry injected at the head of {@code CEActionManager.setCurrentAction(a, N)}:
     * reports exactly which action object claims each gesture event.
     */
    public static void diagSetAction(final Object action, final Object event) {
        try {
            diagnostic("SET_ACTION action=" + (action == null ? "null" : action.getClass().getName())
                    + " event=" + (event == null ? "null" : event.getClass().getName())
                    + " av=" + safeBool(event, "av") + " aw=" + safeBool(event, "aw")
                    + " ax=" + safeBool(event, "ax"));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static boolean safeBool(final Object target, final String name) {
        try {
            return invokeBoolean(target, name);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return false;
        }
    }

    /**
     * Green bezier tick mirror, injected at the head of the reviewed drag
     * dispatcher ({@code warp.a$b.b(N)}). Each drag tick writes bezier points
     * directly per handle kind ({@code CBezierGrid$b}) and edit type, including
     * the opposite-handle tangent continuity and neighbor linkage, and never
     * touches {@code moveToOnLocal}; the same tick's native bake afterwards
     * rewrites {@code positions} from the cage, so mirroring here joins the
     * gesture's own GroupUndo with no second history entry.
     *
     * <p>The mirrored side is replayed through the ref-level native write:
     * a constructed counterpart {@code CBezierGrid$a} (mirrored col/row +
     * mirrored handle kind, propagation flag copied) is converted by
     * {@code getCompatiblePointRef} into the same local space the tick uses,
     * then {@code moveToOnLocal(pos + mirroredDelta, action.b())} runs the
     * host's own per-kind write and propagation on the counterpart. A bare
     * handle-vector write would leave the symmetric side without the native
     * linkage (user report: neighbors' handles move, symmetric ones don't).
     *
     * <p>Axis semantics (r32 feedback): 垂直镜像 = counterpart moves vertically
     * (mirror across the horizontal line: row flips, dy negated, dx follows,
     * CONTROL_N/CONTROL_S swap); 水平镜像 = horizontally (mirror across the
     * vertical line: col flips, dx negated, dy follows, CONTROL_W/E swap).</p>
     */
    public static void mirrorGreenTick(final Object action, final Object event) {
        try {
            if (REPORTED_SKIPS.add("green:entered")) {
                diagnostic("GREEN_TICK_ENTERED action=" + (action == null ? "null" : action.getClass().getName())
                        + " event=" + (event == null ? "null" : event.getClass().getName()));
            }
            final Binding binding = INSTALLED.get();
            if (binding == null || !binding.enabled() || action == null || event == null) {
                greenSkip("NO_BINDING_OR_EVENT");
                return;
            }
            if (!PARTICIPATION.hasParticipants()) {
                greenSkip("NO_PARTICIPANT");
                return;
            }
            final int axis = ARMED_AXIS.get();
            if (axis == 0) {
                greenSkip("AXIS_OFF");
                return;
            }
            // b(N) also dispatches gesture-end (av) events; only live drag
            // ticks (aw) drive the native write we mirror.
            if (!invokeBoolean(event, "aw")) {
                greenSkip("NOT_TICK");
                return;
            }
            final boolean vertical = axis == 1;
            final Field selectionField = action.getClass().getDeclaredField("b");
            selectionField.setAccessible(true);
            final Object selection = selectionField.get(action);
            if (selection == null) {
                greenSkip("NO_SELECTION");
                return;
            }
            final Object ref = invoke(selection, "a", new Class<?>[0]);
            if (ref == null) {
                greenSkip("NO_REF");
                return;
            }
            final Object point = invoke(ref, "b", new Class<?>[0]);
            final int column = invokeInt(ref, "c");
            final int row = invokeInt(ref, "d");
            final String type = String.valueOf(invoke(ref, "e", new Class<?>[0]));
            final Object grid = invoke(ref, "a", new Class<?>[0]);
            if (point == null || grid == null) {
                greenSkip("NO_POINT_OR_GRID");
                return;
            }
            final int bezierCol = invokeInt(grid, "getBezierCol");
            final int bezierRow = invokeInt(grid, "getBezierRow");
            if (bezierCol < 0 || bezierRow < 0) {
                greenSkip("BAD_DIMS col=" + bezierCol + " row=" + bezierRow);
                return;
            }
            final int counterpartCol = vertical ? column : bezierCol - column;
            final int counterpartRow = vertical ? bezierRow - row : row;
            final boolean selfMirrored = counterpartCol == column && counterpartRow == row;
            final Object counterpart;
            if (selfMirrored) {
                // Odd bezier divisions put this point on the axis: it is its
                // own counterpart. The two opposing handles mirror each other
                // within the same point, so the mirrored move targets the
                // opposite handle of the dragged point itself.
                counterpart = point;
            } else {
                final Object table = invoke(grid, "getBezierPtRef", new Class<?>[0]);
                if (!(table instanceof Object[][] columns) || counterpartCol < 0 || counterpartCol >= columns.length) {
                    greenSkip("BAD_TABLE col=" + counterpartCol);
                    return;
                }
                if (!(columns[counterpartCol] instanceof Object[])) {
                    greenSkip("BAD_COLUMN col=" + counterpartCol);
                    return;
                }
                final Object[] counterpartColumnList = (Object[]) columns[counterpartCol];
                if (counterpartRow < 0 || counterpartRow >= counterpartColumnList.length) {
                    greenSkip("BAD_ROW row=" + counterpartRow + " len=" + counterpartColumnList.length);
                    return;
                }
                counterpart = counterpartColumnList[counterpartRow];
                if (counterpart == null) {
                    greenSkip("NULL_COUNTERPART");
                    return;
                }
            }
            final Object delta = invoke(event, "aH", new Class<?>[0]);
            if (delta == null) {
                greenSkip("NO_DELTA");
                return;
            }
            final float dx = invokeFloat(delta, "getX");
            final float dy = invokeFloat(delta, "getY");
            if (Math.abs(dx) <= AltAxisMirrorMath.MOVE_EPSILON && Math.abs(dy) <= AltAxisMirrorMath.MOVE_EPSILON) {
                greenSkip("TINY_DELTA");
                return;
            }
            final String counterType = counterpartHandleType(type, vertical);
            if (selfMirrored && counterType.equals(type)) {
                // The handle offset lies along the axis: its mirror is itself
                // and the native drag already moved it — nothing to mirror.
                greenSkip("SELF_ON_AXIS");
                return;
            }
            // Re-dispatch the gesture through the ref-level native write so the
            // counterpart side receives the same per-kind propagation the tick
            // performs on the dragged side (opposite-handle tangent continuity
            // and neighbor linkage) — a lone handle write lags behind.
            final Object counterRef = bezierCounterpartRef(
                    ref, grid, counterpart, counterpartCol, counterpartRow, counterType);
            if (counterRef == null) {
                greenSkip("NO_COUNTERPART_REF");
                return;
            }
            final Object space = bezierLocalSpace(grid.getClass().getClassLoader());
            if (space == null) {
                greenSkip("NO_LOCAL_SPACE");
                return;
            }
            final Method compatMethod =
                    MethodHandleCache.declaredByArity(grid.getClass(), "getCompatiblePointRef", 2);
            final Object compat = compatMethod.invoke(grid, counterRef, space);
            if (compat == null) {
                greenSkip("NO_COMPAT_REF");
                return;
            }
            final Object pos = invoke(compat, "getPos", new Class<?>[0]);
            if (pos == null) {
                greenSkip("NO_POS");
                return;
            }
            final float mirroredDx = vertical ? dx : -dx;
            final float mirroredDy = vertical ? -dy : dy;
            final float weight = invokeFloat(action, "b");
            final Class<?> vectorType = pos.getClass();
            final Object target = vectorType
                    .getConstructor(float.class, float.class)
                    .newInstance(invokeFloat(pos, "getX") + mirroredDx, invokeFloat(pos, "getY") + mirroredDy);
            invoke(compat, "moveToOnLocal", new Class<?>[] {vectorType, float.class}, target, weight);
            if (GREEN_APPLIED_REPORTED.compareAndSet(false, true)) {
                diagnostic("MIRROR_GREEN_APPLIED axis=" + (vertical ? "vertical" : "horizontal")
                        + " kind=" + type + "->" + counterType);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            greenSkip("FAILED_" + failure.getClass().getSimpleName());
        }
    }

    /**
     * Builds the counterpart {@code CBezierGrid$a} for the mirrored
     * re-dispatch via the reviewed seven-argument public constructor
     * {@code (grid, pos, pt, col, row, kind, flag)}. {@code pos} is only used
     * for hit-testing — {@code moveToOnLocal} never reads it — so the source
     * ref's position is a safe placeholder; the propagation flag {@code f} is
     * copied verbatim from the dragged ref.
     */
    private static Object bezierCounterpartRef(
            final Object ref,
            final Object grid,
            final Object counterpartPt,
            final int counterpartCol,
            final int counterpartRow,
            final String counterType)
            throws ReflectiveOperationException {
        final Class<?> refClass = ref.getClass();
        final Object kind = invoke(ref, "e", new Class<?>[0]);
        if (kind == null) {
            return null;
        }
        final Class<?> kindType = kind.getClass();
        @SuppressWarnings({"unchecked", "rawtypes"})
        final Object kindConstant = java.lang.Enum.valueOf((Class) kindType, counterType);
        final Field flagField = refClass.getDeclaredField("f");
        flagField.setAccessible(true);
        final boolean flag = flagField.getBoolean(ref);
        for (final Constructor<?> ctor : refClass.getConstructors()) {
            final Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 7
                    && params[0].isInstance(grid)
                    && params[2].isInstance(counterpartPt)
                    && params[3] == int.class && params[4] == int.class
                    && params[5].isAssignableFrom(kindType)
                    && params[6] == boolean.class) {
                ctor.setAccessible(true);
                return ctor.newInstance(
                        grid, invoke(ref, "getPos", new Class<?>[0]), counterpartPt,
                        counterpartCol, counterpartRow, kindConstant, flag);
            }
        }
        return null;
    }

    /**
     * Resolves the local selection space the tick converts refs into:
     * {@code com.live2d.doc.selection.d.b} (a static {@code d$a} field) {@code .a()}.
     */
    private static Object bezierLocalSpace(final ClassLoader loader)
            throws ReflectiveOperationException {
        final Class<?> spaceType = Class.forName("com.live2d.doc.selection.d", false, loader);
        final Field companionField = spaceType.getDeclaredField("b");
        companionField.setAccessible(true);
        final Object companion = companionField.get(null);
        if (companion == null) {
            return null;
        }
        return companion.getClass().getMethod("a").invoke(companion);
    }

    /** Maps a dragged handle type to the axis-mirrored counterpart handle type. */
    private static String counterpartHandleType(final String draggedType, final boolean vertical) {
        // 垂直镜像 (Y flip, up/down): CONTROL_N <-> CONTROL_S; W/E keep.
        // 水平镜像 (X flip, left/right): CONTROL_W <-> CONTROL_E; N/S keep.
        return switch (draggedType) {
            case "CONTROL_N" -> vertical ? "CONTROL_S" : "CONTROL_N";
            case "CONTROL_S" -> vertical ? "CONTROL_N" : "CONTROL_S";
            case "CONTROL_W" -> vertical ? "CONTROL_W" : "CONTROL_E";
            case "CONTROL_E" -> vertical ? "CONTROL_E" : "CONTROL_W";
            default -> draggedType;
        };
    }

    /** Route stage marker with the event's modifier snapshot. */
    public static void diagRoute(final Object stage, final Object event) {
        try {
            diagEventModifiers(event, stage instanceof String name ? name : "ROUTE");
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("ROUTE_FAILED reason=" + failure.getClass().getName());
        }
    }

    /** Route diagnostics: reports the doc-level point write with index and position when resolvable. */
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
            FatalErrors.rethrowIfFatal(failure);
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
            FatalErrors.rethrowIfFatal(failure);
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
            FatalErrors.rethrowIfFatal(ignored);
            // never reach the host call site
        }
    }

    private static void skipOnce(final String reason) {
        if (REPORTED_SKIPS.add(reason)) {
            diagnostic("TICK_SKIP reason=" + reason);
        }
    }

    private static void greenSkip(final String reason) {
        if (REPORTED_SKIPS.add("green:" + reason)) {
            diagnostic("GREEN_SKIP reason=" + reason);
        }
    }

    private static void diagnostic(final String stage) {
        try {
            DIAGNOSTIC.get().accept("WARP_ALT_MIRROR_DIAG stage=" + stage);
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            // Diagnostics must never reach the host call site.
        }
    }

    /** Shared mirror math constants for the warp axis bridge. */
    static final class AltAxisMirrorMath {
        /** Displacement below this magnitude is treated as "did not move". */
        static final float MOVE_EPSILON = 1.0e-3f;

        private AltAxisMirrorMath() {}
    }
}
