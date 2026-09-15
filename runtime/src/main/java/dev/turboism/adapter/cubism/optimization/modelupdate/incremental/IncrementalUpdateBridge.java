package dev.turboism.adapter.cubism.optimization.modelupdate.incremental;

import dev.turboism.adapter.cubism.optimization.modelupdate.incremental.IncrementalUpdateTarget.Dep;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for per-object incremental model updates (slice B).
 *
 * <p>Installed as JDK functional-interface values in {@link System#getProperties()} so the
 * injected host bytecode needs no Turboism classes. The installer flips the updater
 * singleton's {@code optimize_skipInterpolationIfParameterNotUpdated} flag; the host's own
 * {@code keyformGrid.c()} then skips interpolation for objects whose bound parameters did
 * not change. Rewritten call sites ask this bridge whether each deformer still needs
 * {@code setDirtyDeformedForm(true)} and whether each ArtMesh still needs its deformed
 * transform; unchanged objects keep last frame's {@code interpolatedForm/deformedForm}.</p>
 *
 * <p>Every gate is fail-open: a disabled flag, an unknown object or any thrown error
 * performs the native mark/transform so stale output is impossible. Per-frame state lives
 * in identity-keyed sets cleared by {@code beginUpdate} at the update-core entry.</p>
 */
public final class IncrementalUpdateBridge implements AutoCloseable {

    /** Opt-out disable switch (enabled unless set to {@code false}), re-read every frame. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.incrementalUpdate";
    /** Slot for the {@code Consumer<Object>} update-core entry callback ({model, ctx}). */
    public static final String BEGIN_PROPERTY = "turboism.incremental-update.begin";
    /** Slot for the {@code Consumer<Object>} update-core return callback ({model, ctx}). */
    public static final String END_PROPERTY = "turboism.incremental-update.end";
    /** Slot for the {@code BiConsumer<Object,Object>} dirty-mark call site (deformer, model). */
    public static final String MARK_PROPERTY = "turboism.incremental-update.mark";
    /** Slot for the {@code Function<Object[],Object>} artMesh deform call site. */
    public static final String DEFORM_PROPERTY = "turboism.incremental-update.deform";
    /** Slot for the {@code Consumer<Object>} interpolate-entry recorder (form instance). */
    public static final String FORM_PROPERTY = "turboism.incremental-update.form";
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.incremental-update.stats";
    /** Probe mode: alternating forced-full updates whose drawable digests must match. */
    public static final String PROBE_PROPERTY = "turboism.incremental-update.probe";
    /** Probe mismatch report path (JSON lines, appended). */
    public static final String RESULT_PROPERTY = "turboism.incremental-update.probe.result";

    private static final String CM = "com.live2d.cubism.doc.model.CModel";
    private static final String PS = "com.live2d.cubism.doc.model.param.CParameterSet";
    private static final String CP = "com.live2d.cubism.doc.model.param.CParameter";
    private static final String CX = "com.live2d.cubism.view.context.CEViewContext";
    private static final String DOC = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String ACD = "com.live2d.cubism.doc.model.deformer.ACDeformer";
    private static final String CAM = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMesh";
    private static final String MF = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String PF = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathForm";
    private static final String DR = "com.live2d.cubism.doc.model.drawable.ACDrawable";
    private static final String PP = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathPoint";
    private static final String SP = "com.live2d.graphics.splineCurve.CSplineCurvePoint";
    private static final String GV = "com.live2d.graphics3d.type.GVector2";

    private final IncrementalUpdateTarget target;
    private final Class<?> modelingDocumentType, meshFormType, pathFormType;
    private final MethodHandle getSkipInterpolation, setSkipInterpolation,
        getAllDeformers, getAllArtPaths, getAllAffecters, getDeformer,
        getParameterSet, getAllDrawables, getParameters, getUpdateVersion, paramValue,
        getLastModifiedTime, getDoc,
        getDirty, setDirty, deformerInterpolatedForm, deformerAnimatedForm,
        getTargetDeformerGuid, getDeformerGuid, createTransform,
        meshInterpolatedForm, meshAnimatedForm, meshTransform,
        getDeformedForm, getDrawOrder, meshPositions, pathPositions,
        pointCurve, pointWidth, pointOpacity, spPoint, spStart, spEnd, vecX, vecY;
    private final Object updaterInstance;
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder epochs = new LongAdder(), markCalls = new LongAdder(),
        markedDirty = new LongAdder(), markedClean = new LongAdder(),
        descendantsMarked = new LongAdder(), meshDeformed = new LongAdder(),
        meshSkipped = new LongAdder(), formsRecorded = new LongAdder(),
        probePairs = new LongAdder(), probeSkippedPairs = new LongAdder(),
        probeMismatch = new LongAdder(), failures = new LongAdder(),
        digestNanos = new LongAdder();
    private final Consumer<Object> beginCallback = this::beginUpdate;
    private final Consumer<Object> endCallback = this::afterUpdate;
    private final BiConsumer<Object, Object> markCallback = this::markDirty;
    private final Function<Object[], Object> deformCallback = this::deformArtMesh;
    private final Consumer<Object> formCallback = this::formInterpolated;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private final Set<Object> changedForms =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> markedDeformers =
        Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean epochFull, epochUnsafe, priorSkipInterpolation;
    private volatile byte[] previousDigest, previousInputs, currentInputs;
    private volatile boolean previousNarrowed;
    private volatile Properties installedProperties;
    private volatile Map<Object, List<Object>> childMap;
    private volatile Object childMapModel;

    /**
     * Resolves every dependency method of {@code target} on {@code loader}'s host classes.
     *
     * @throws ReflectiveOperationException when any reviewed getter is absent or mistyped
     */
    public IncrementalUpdateBridge(final IncrementalUpdateTarget target,
                                   final ClassLoader loader)
            throws ReflectiveOperationException {
        this.target = target;
        modelingDocumentType = Class.forName(DOC, false, loader);
        meshFormType = Class.forName(MF, false, loader);
        pathFormType = Class.forName(PF, false, loader);
        final Map<Dep, MethodHandle> handles = resolve(target, loader);
        final String up = target.updater().replace('/', '.');
        getSkipInterpolation = handles.get(dep(up, "b", "()Z"));
        setSkipInterpolation = handles.get(dep(up, "b", "(Z)V"));
        getAllDeformers = handles.get(dep(CM, "getAllDeformers"));
        getAllArtPaths = handles.get(dep(CM, "getAllArtPaths"));
        getAllAffecters = handles.get(dep(CM, "getAllAffecters"));
        getDeformer = handles.get(dep(CM, "getDeformer"));
        getParameterSet = handles.get(dep(CM, "getParameterSet"));
        getAllDrawables = handles.get(dep(CM, "getAllDrawables"));
        getParameters = handles.get(dep(PS, "getParameters"));
        getUpdateVersion = handles.get(dep(PS, "getUpdateVersion"));
        paramValue = handles.get(dep(CP, "getValue"));
        getLastModifiedTime = handles.get(dep(DOC, "getLastModifiedTime"));
        getDoc = handles.get(dep(CX, "getDoc"));
        getDirty = handles.get(dep(ACD, "getDirtyDeformedForm"));
        setDirty = handles.get(dep(ACD, "setDirtyDeformedForm"));
        deformerInterpolatedForm = handles.get(dep(ACD, "getInterpolatedForm"));
        deformerAnimatedForm = handles.get(dep(ACD, "getLocalAnimatedForm"));
        getTargetDeformerGuid = handles.get(dep(ACD, "getTargetDeformerGuid"));
        getDeformerGuid = handles.get(dep(ACD, "getGuid"));
        createTransform = handles.get(dep(ACD, "getCreateLocalToCanvasTransform"));
        meshInterpolatedForm = handles.get(dep(CAM, "getInterpolatedForm"));
        meshAnimatedForm = handles.get(dep(CAM, "getLocalAnimatedForm"));
        meshTransform = handles.get(dep(MF, "transform"));
        getDeformedForm = handles.get(dep(DR, "getDeformedForm"));
        getDrawOrder = handles.get(dep(DR, "getDrawOrder"));
        meshPositions = handles.get(dep(MF, "getPositions"));
        pathPositions = handles.get(dep(PF, "getPositions"));
        pointCurve = handles.get(dep(PP, "getCurvePointPosition"));
        pointWidth = handles.get(dep(PP, "getWidth"));
        pointOpacity = handles.get(dep(PP, "getOpacity"));
        spPoint = handles.get(dep(SP, "getPoint"));
        spStart = handles.get(dep(SP, "getStartVelocity"));
        spEnd = handles.get(dep(SP, "getEndVelocity"));
        vecX = handles.get(dep(GV, "getX"));
        vecY = handles.get(dep(GV, "getY"));
        updaterInstance = Class.forName(up, false, loader).getField("a").get(null);
    }

    private Dep dep(final String owner, final String name, final String descriptor) {
        for (final Dep d : target.dependencies()) {
            if (d.owner().equals(owner) && d.name().equals(name)
                && d.descriptor().equals(descriptor)) return d;
        }
        throw new IllegalArgumentException(
            "undeclared dependency " + owner + "." + name + descriptor);
    }

    private Dep dep(final String owner, final String name) {
        Dep found = null;
        for (final Dep d : target.dependencies()) {
            if (d.owner().equals(owner) && d.name().equals(name)) {
                if (found != null) throw new IllegalArgumentException(
                    "ambiguous dependency " + owner + "." + name);
                found = d;
            }
        }
        if (found == null) throw new IllegalArgumentException(
            "undeclared dependency " + owner + "." + name);
        return found;
    }

    private static Map<Dep, MethodHandle> resolve(final IncrementalUpdateTarget target,
                                                  final ClassLoader loader)
            throws ReflectiveOperationException {
        final var lookup = MethodHandles.publicLookup();
        final Map<Dep, MethodHandle> handles = new HashMap<>();
        for (final Dep dep : target.dependencies()) {
            final Class<?> owner = Class.forName(dep.owner(), false, loader);
            final Method method = method(owner, dep);
            final MethodType invoked = invokedType(method);
            handles.put(dep, lookup.unreflect(method).asType(invoked));
        }
        return Map.copyOf(handles);
    }

    /** Erased {@code (Object...)->primitive-or-Object} invocation signature. */
    private static MethodType invokedType(final Method method) {
        final Class<?>[] erased = new Class<?>[method.getParameterCount() + 1];
        Arrays.fill(erased, Object.class);
        final Class<?> type = method.getReturnType();
        return MethodType.methodType(type.isPrimitive() ? type : Object.class, erased);
    }

    private static Method method(final Class<?> owner, final Dep dep) throws NoSuchMethodException {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(dep.name())) continue;
            final String descriptor = MethodType.methodType(
                method.getReturnType(), method.getParameterTypes()).descriptorString();
            if (descriptor.equals(dep.descriptor())) return method;
        }
        throw new NoSuchMethodException(owner.getName() + "." + dep.name() + dep.descriptor());
    }

    /** Occupies the callback slots and enables the host skip flag; refuses double install. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("incremental update already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(BEGIN_PROPERTY) || properties.containsKey(END_PROPERTY)
                || properties.containsKey(MARK_PROPERTY) || properties.containsKey(DEFORM_PROPERTY)
                || properties.containsKey(FORM_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("incremental update slots occupied");
            }
            try {
                properties.put(BEGIN_PROPERTY, beginCallback);
                properties.put(END_PROPERTY, endCallback);
                properties.put(MARK_PROPERTY, markCallback);
                properties.put(DEFORM_PROPERTY, deformCallback);
                properties.put(FORM_PROPERTY, formCallback);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(BEGIN_PROPERTY, beginCallback);
                properties.remove(END_PROPERTY, endCallback);
                properties.remove(MARK_PROPERTY, markCallback);
                properties.remove(DEFORM_PROPERTY, deformCallback);
                properties.remove(FORM_PROPERTY, formCallback);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
        try {
            priorSkipInterpolation = (boolean) getSkipInterpolation.invokeExact(updaterInstance);
            setSkipInterpolation.invokeExact(updaterInstance, (Object) Boolean.TRUE);
        } catch (Throwable failure) {
            close();
            throw new IllegalStateException("host skip flag not writable", failure);
        }
    }

    /** The optimization is enabled unless the property is set to {@code false}. */
    public static boolean flagEnabled() {
        try {
            return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY));
        } catch (SecurityException denied) {
            return false;
        }
    }

    private boolean enabled() {
        return active.get() && flagEnabled();
    }

    /**
     * Update-core entry: starts a fresh epoch, decides whether probe forces a full update,
     * applies the host skip flag and recomputes the per-model unsafe-input check.
     */
    private void beginUpdate(final Object args) {
        try {
            epochFull = true;
            epochUnsafe = true;
            if (!(args instanceof Object[] pair) || pair.length < 2 || pair[0] == null) return;
            final Object model = pair[0], ctx = pair[1];
            epochs.increment();
            changedForms.clear();
            markedDeformers.clear();
            childMap = null;
            childMapModel = null;
            final boolean enabled = enabled();
            final boolean probe = Boolean.getBoolean(PROBE_PROPERTY);
            epochFull = !enabled || (probe && (epochs.sum() & 1L) == 1L);
            setSkipInterpolation.invokeExact(updaterInstance,
                (Object) Boolean.valueOf(enabled && !epochFull));
            epochUnsafe = !enabled || unsafe(model);
            if (probe) {
                currentInputs = inputsVersion(model, ctx);
            }
        } catch (Throwable failure) {
            epochFull = true;
            epochUnsafe = true;
            failures.increment();
        }
    }

    /** Records an interpolated form instance; recorded means "parameters changed". */
    private void formInterpolated(final Object form) {
        try {
            if (form != null) {
                changedForms.add(form);
                formsRecorded.increment();
            }
        } catch (Throwable failure) {
            failures.increment();
        }
    }

    /**
     * The loop-1 dirty-mark call site: sets the host flag only when this deformer is proven
     * changed, propagated-dirty or animated; anything unknown marks dirty (full fallback).
     */
    private void markDirty(final Object deformer, final Object model) {
        markCalls.increment();
        try {
            if (deformer == null || model == null) {
                if (deformer != null) setDirty.invokeExact(deformer, (Object) Boolean.TRUE);
                return;
            }
            if (epochFull) {
                setDirty.invokeExact(deformer, (Object) Boolean.TRUE);
                return;
            }
            final boolean dirty = (boolean) getDirty.invokeExact(deformer)
                || changedForms.contains(
                    (Object) deformerInterpolatedForm.invokeExact(deformer))
                || (Object) deformerAnimatedForm.invokeExact(deformer) != null
                || ancestorChanged(deformer, model);
            if (dirty) {
                setDirty.invokeExact(deformer, (Object) Boolean.TRUE);
                markedDeformers.add(deformer);
                markedDirty.increment();
                markDescendants(deformer, model);
            } else {
                markedClean.increment();
            }
        } catch (Throwable failure) {
            failures.increment();
            try {
                if (deformer != null) setDirty.invokeExact(deformer, (Object) Boolean.TRUE);
            } catch (Throwable nested) {
                failures.increment();
            }
        }
    }

    /** Any ancestor marked this frame, parameter-changed or animated propagates dirt. */
    private boolean ancestorChanged(final Object deformer, final Object model) throws Throwable {
        visited.clear();
        Object current = deformer;
        for (int depth = 0; depth < 256; depth++) {
            if (!visited.add(current)) return false;
            final Object guid = (Object) getTargetDeformerGuid.invokeExact(current);
            if (guid == null) return false;
            final Object parent = (Object) getDeformer.invokeExact(model, guid);
            if (parent == null || parent == current) return false;
            if (markedDeformers.contains(parent)
                || (boolean) getDirty.invokeExact(parent)
                || changedForms.contains(
                    (Object) deformerInterpolatedForm.invokeExact(parent))
                || (Object) deformerAnimatedForm.invokeExact(parent) != null) {
                return true;
            }
            current = parent;
        }
        return true;
    }

    /** Eagerly flags every descendant so later-processed children still transform. */
    private void markDescendants(final Object deformer, final Object model) throws Throwable {
        final Map<Object, List<Object>> children = children(model);
        final ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(deformer);
        while (!pending.isEmpty()) {
            final Object parent = pending.removeFirst();
            final Object guid = (Object) getDeformerGuid.invokeExact(parent);
            final List<Object> kids = children.get(guid);
            if (kids == null) continue;
            for (final Object child : kids) {
                if (markedDeformers.add(child)) {
                    setDirty.invokeExact(child, (Object) Boolean.TRUE);
                    descendantsMarked.increment();
                    pending.add(child);
                }
            }
        }
    }

    /** Parent-guid → direct children index for this epoch's model, built on first use. */
    private Map<Object, List<Object>> children(final Object model) throws Throwable {
        if (childMap != null && childMapModel == model) return childMap;
        final Map<Object, List<Object>> map = new HashMap<>();
        final Object all = (Object) getAllDeformers.invokeExact(model);
        if (all instanceof List) {
            for (final Object candidate : (List<?>) all) {
                if (candidate == null) continue;
                final Object guid = (Object) getTargetDeformerGuid.invokeExact(candidate);
                map.computeIfAbsent(guid, key -> new ArrayList<>(2)).add(candidate);
            }
        }
        childMap = map;
        childMapModel = model;
        return map;
    }

    /**
     * The artMesh deform call site: runs the native transform when the mesh, its target
     * deformer or the model's unsafe inputs changed; otherwise keeps last deformedForm.
     *
     * @param args {@code [preDeformForm, targetDeformer, deformedFormOut, artMesh]}
     */
    private Object deformArtMesh(final Object[] args) {
        try {
            if (args == null || args.length < 4) {
                throw new IllegalStateException("deform args absent");
            }
            final Object preDeform = args[0], target3 = args[1], out = args[2], mesh = args[3];
            final boolean deform = epochFull || epochUnsafe || target3 == null || out == null
                || changedForms.contains((Object) meshInterpolatedForm.invokeExact(mesh))
                || (Object) meshAnimatedForm.invokeExact(mesh) != null
                || markedDeformers.contains(target3);
            if (!deform) {
                meshSkipped.increment();
                return out;
            }
            meshDeformed.increment();
            final Object transform = (Object) createTransform.invokeExact(target3);
            return (Object) meshTransform.invokeExact(preDeform, transform, out);
        } catch (Throwable failure) {
            failures.increment();
            // Rethrow so the injected fallback re-executes the exact native sequence.
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException(failure);
        }
    }

    /** Update-core return: probe digest comparison against the next forced-full epoch. */
    private void afterUpdate(final Object args) {
        try {
            if (!Boolean.getBoolean(PROBE_PROPERTY)
                || !(args instanceof Object[] pair) || pair.length < 1 || pair[0] == null) return;
            final Object model = pair[0];
            final byte[] digest;
            try {
                digest = digest(model);
            } catch (Throwable failure) {
                probeMismatch.increment();
                writeProbe(null, failure);
                previousDigest = null;
                previousNarrowed = false;
                return;
            }
            if (previousNarrowed && epochFull && previousDigest != null
                && previousInputs != null) {
                if (currentInputs != null && Arrays.equals(previousInputs, currentInputs)) {
                    probePairs.increment();
                    if (!Arrays.equals(previousDigest, digest)) {
                        probeMismatch.increment();
                        writeProbe(digest, null);
                    }
                } else {
                    probeSkippedPairs.increment();
                }
            }
            previousDigest = digest;
            previousNarrowed = !epochFull;
            previousInputs = currentInputs;
        } catch (Throwable failure) {
            failures.increment();
        }
    }

    /**
     * A coarse fingerprint of the inputs that legitimately change drawable output between
     * adjacent epochs: parameter-set version and values plus the document timestamp.
     */
    private byte[] inputsVersion(final Object model, final Object ctx) throws Throwable {
        final MessageDigest md = MessageDigest.getInstance("SHA-256");
        final ByteBuffer buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
        final Object parameterSet = (Object) getParameterSet.invokeExact(model);
        if (parameterSet == null) {
            putInt(md, buffer, -1);
        } else {
            putInt(md, buffer, (int) getUpdateVersion.invokeExact(parameterSet));
            final Object parameters = (Object) getParameters.invokeExact(parameterSet);
            if (parameters instanceof List) {
                putInt(md, buffer, ((List<?>) parameters).size());
                for (final Object parameter : (List<?>) parameters) {
                    putInt(md, buffer,
                        parameter == null ? -1
                            : Float.floatToRawIntBits((float) paramValue.invokeExact(parameter)));
                }
            } else {
                putInt(md, buffer, -2);
            }
        }
        Object document = ctx == null ? null : (Object) getDoc.invokeExact(ctx);
        if (!modelingDocumentType.isInstance(document)) document = null;
        putLong(md, buffer, document == null ? Long.MIN_VALUE
            : (long) getLastModifiedTime.invokeExact(document));
        buffer.flip();
        md.update(buffer);
        return md.digest();
    }

    /** ArtPath glue or affecter meshes write into mesh vertices; those models stay native. */
    private boolean unsafe(final Object model) throws Throwable {
        final Object paths = (Object) getAllArtPaths.invokeExact(model);
        final Object affecters = (Object) getAllAffecters.invokeExact(model);
        return !(paths instanceof List) || !(affecters instanceof List)
            || !((List<?>) paths).isEmpty() || !((List<?>) affecters).isEmpty();
    }

    private byte[] digest(final Object model) throws Throwable {
        final long started = System.nanoTime();
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final Object drawables = (Object) getAllDrawables.invokeExact(model);
            if (!(drawables instanceof List)) throw new IllegalStateException("drawables absent");
            final ByteBuffer buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
            for (final Object drawable : (List<?>) drawables) {
                if (drawable == null) throw new IllegalStateException("null drawable");
                final Object form = (Object) getDeformedForm.invokeExact(drawable);
                if (form == null) {
                    putInt(md, buffer, 0);
                } else if (meshFormType.isInstance(form)) {
                    final float[] positions = (float[]) (Object) meshPositions.invokeExact(form);
                    putInt(md, buffer, 1);
                    if (positions == null) {
                        putInt(md, buffer, -1);
                    } else {
                        putInt(md, buffer, positions.length);
                        for (final float position : positions) putFloat(md, buffer, position);
                    }
                } else if (pathFormType.isInstance(form)) {
                    final Object points = (Object) pathPositions.invokeExact(form);
                    putInt(md, buffer, 2);
                    if (points == null) {
                        putInt(md, buffer, -1);
                    } else if (!(points instanceof List)) {
                        throw new IllegalStateException(
                            "path points of unexpected type " + points.getClass().getName());
                    } else {
                        putInt(md, buffer, ((List<?>) points).size());
                        for (final Object point : (List<?>) points) {
                            if (point == null) {
                                putInt(md, buffer, -1);
                                continue;
                            }
                            putInt(md, buffer, 1);
                            final Object curve = (Object) pointCurve.invokeExact(point);
                            if (curve == null) {
                                putInt(md, buffer, -1);
                            } else {
                                putVector(md, buffer, (Object) spPoint.invokeExact(curve));
                                putVector(md, buffer, (Object) spStart.invokeExact(curve));
                                putVector(md, buffer, (Object) spEnd.invokeExact(curve));
                            }
                            putFloat(md, buffer, (float) pointWidth.invokeExact(point));
                            putFloat(md, buffer, (float) pointOpacity.invokeExact(point));
                        }
                    }
                } else {
                    throw new IllegalStateException("undigestable form " + form.getClass().getName());
                }
                putInt(md, buffer, (int) getDrawOrder.invokeExact(drawable));
            }
            buffer.flip();
            md.update(buffer);
            return md.digest();
        } finally {
            digestNanos.add(System.nanoTime() - started);
        }
    }

    private void putVector(final MessageDigest md, final ByteBuffer buffer, final Object vector)
            throws Throwable {
        if (vector == null) {
            putInt(md, buffer, -1);
            return;
        }
        putInt(md, buffer, 1);
        putFloat(md, buffer, (float) vecX.invokeExact(vector));
        putFloat(md, buffer, (float) vecY.invokeExact(vector));
    }

    private static void putFloat(final MessageDigest md, final ByteBuffer buffer, final float value) {
        putInt(md, buffer, Float.floatToRawIntBits(value));
    }

    private static void putInt(final MessageDigest md, final ByteBuffer buffer, final int value) {
        if (buffer.remaining() < Integer.BYTES) {
            buffer.flip();
            md.update(buffer);
            buffer.clear();
        }
        buffer.putInt(value);
    }

    private static void putLong(final MessageDigest md, final ByteBuffer buffer, final long value) {
        putInt(md, buffer, (int) (value >>> 32));
        putInt(md, buffer, (int) value);
    }

    private void writeProbe(final byte[] post, final Throwable failure) {
        try {
            final String path = System.getProperty(RESULT_PROPERTY);
            if (path == null || path.isBlank()) return;
            final StringBuilder json = new StringBuilder(256).append('{');
            json.append("\"target\":\"").append(target.version()).append('\"');
            if (post != null) {
                json.append(",\"postDigest\":\"").append(HexFormat.of().formatHex(post)).append('\"');
            }
            if (failure != null) {
                json.append(",\"error\":\"").append(escape(describe(failure))).append('\"');
            }
            json.append("}\n");
            Files.writeString(Path.of(path), json.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            failures.increment();
        }
    }

    /** Exception class/message plus the top frames, for offline diagnosis. */
    private static String describe(final Throwable failure) {
        final StringBuilder text = new StringBuilder(256).append(failure);
        final StackTraceElement[] trace = failure.getStackTrace();
        for (int i = 0; i < Math.min(trace.length, 4); i++) {
            text.append(" @ ").append(trace[i]);
        }
        return text.toString();
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    /** Work counts only; no interaction benefit is inferred from them. */
    public Map<String, Long> snapshot() {
        return Map.ofEntries(
            Map.entry("active", active.get() ? 1L : 0L),
            Map.entry("epochs", epochs.sum()),
            Map.entry("markCalls", markCalls.sum()),
            Map.entry("markedDirty", markedDirty.sum()),
            Map.entry("markedClean", markedClean.sum()),
            Map.entry("descendantsMarked", descendantsMarked.sum()),
            Map.entry("meshDeformed", meshDeformed.sum()),
            Map.entry("meshSkipped", meshSkipped.sum()),
            Map.entry("formsRecorded", formsRecorded.sum()),
            Map.entry("probePairs", probePairs.sum()),
            Map.entry("probeSkippedPairs", probeSkippedPairs.sum()),
            Map.entry("probeMismatch", probeMismatch.sum()),
            Map.entry("failures", failures.sum()),
            Map.entry("digestNanos", digestNanos.sum()));
    }

    /** Clears owned slots and restores the host skip flag to its pre-install value. */
    @Override public synchronized void close() {
        active.set(false);
        try {
            setSkipInterpolation.invokeExact(updaterInstance,
                (Object) Boolean.valueOf(priorSkipInterpolation));
        } catch (Throwable failure) {
            failures.increment();
        }
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(BEGIN_PROPERTY, beginCallback);
            properties.remove(END_PROPERTY, endCallback);
            properties.remove(MARK_PROPERTY, markCallback);
            properties.remove(DEFORM_PROPERTY, deformCallback);
            properties.remove(FORM_PROPERTY, formCallback);
            properties.remove(STATS_PROPERTY, statistics);
        }
    }
}
