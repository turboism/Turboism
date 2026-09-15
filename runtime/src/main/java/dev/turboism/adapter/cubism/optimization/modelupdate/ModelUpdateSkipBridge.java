package dev.turboism.adapter.cubism.optimization.modelupdate;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipTarget.Dep;
import dev.turboism.adapter.cubism.optimization.modelupdate.UnchangedFramePredicate.Frame;
import dev.turboism.adapter.cubism.optimization.modelupdate.UnchangedFramePredicate.ParamSet;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the unchanged-frame model-update skip.
 *
 * <p>Installed as JDK functional-interface values in {@link System#getProperties()} so the
 * injected host bytecode needs no Turboism classes. Every dependent getter is resolved once
 * at construction through the reviewed {@link ModelUpdateSkipTarget} dependency table and
 * cached as a primitive-typed {@link MethodHandle}; per-frame work is one args-array read,
 * one snapshot record, indexed parameter comparison and a handful of direct handle calls —
 * no reflection lookup, no collection allocation. Any failure anywhere returns
 * {@code false}/swallows so the native path always runs.</p>
 */
public final class ModelUpdateSkipBridge implements AutoCloseable {

    /** Default-off request and live disable switch, re-read every frame. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.modelUpdateSkip";
    /** Slot for the {@code Predicate<Object[]>} skip callback. */
    public static final String CALLBACK_PROPERTY = "turboism.model-update-skip.callback";
    /** Slot for the {@code Consumer<Object>} after-update callback receiving the CModel. */
    public static final String AFTER_PROPERTY = "turboism.model-update-skip.after";
    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.model-update-skip.stats";
    /** Probe mode: decided skips still run native and diff drawable digests. */
    public static final String PROBE_PROPERTY = "turboism.model-update-skip.probe";
    /** Probe mismatch report path (JSON lines, appended). */
    public static final String RESULT_PROPERTY = "turboism.model-update-skip.probe.result";

    private static final String MV = "com.live2d.cubism.view.context.CEViewContext_ModelingView";
    private static final String DOC = "com.live2d.cubism.doc.modeling.CModelingDocument";
    private static final String MF = "com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm";
    private static final String PF = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathForm";
    private static final String AS = "com.live2d.cubism.setting.AppSetting";
    private static final String FT = "com.live2d.cubism.doc.animation.formAnimation.t";

    private final ModelUpdateSkipTarget target;
    private final Class<?> modelingViewType, modelingDocumentType, meshFormType, pathFormType;
    private final MethodHandle getParameterSet, getLastUpdatedParameterSet, getAllDrawables,
        getSource, getParameters, getUpdateVersion, paramValue, paramId, getLastModifiedTime,
        getDoc, getDevelopSetting, getAppearanceSetting, getCurrentEditMode,
        isRandomPose, isExternalApp, isRecording, getCurrentViewMode,
        developH, developK, appearanceD,
        getDraw, optimizeArtMesh, optimizeDeformer, optimizeDrawOrder, optimizeHierarchy,
        getGui, getWarning, maskWarning, blendWarning, getCanvas, hideSelected,
        getDeveloper, highLightDeformerChild,
        formAnimationGate, isModelEditing,
        ucA, ucB, ucC, ucD, ucE, ucF, ucG, ucH, ucSelection,
        ucAliasStack, ucRenderHash, ucRenderCodes,
        getDeformedForm, getDrawOrder, meshPositions, pathPositions,
        pointCurve, pointWidth, pointOpacity, spPoint, spStart, spEnd, vecX, vecY,
        updaterA, updaterB;
    private final Object appSettingInstance, formAnimationInstance;
    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder calls = new LongAdder(), skipped = new LongAdder(),
        full = new LongAdder(), probeMismatch = new LongAdder(), failures = new LongAdder(),
        predicateNanos = new LongAdder(), digestNanos = new LongAdder();
    private final java.util.concurrent.atomic.AtomicLong predicateMaxNanos =
        new java.util.concurrent.atomic.AtomicLong();
    private final Predicate<Object[]> callback = this::shouldSkip;
    private final Consumer<Object> afterUpdate = this::updateCompleted;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private final HostParamSet currentParams = new HostParamSet();
    private final HostParamSet lastUpdatedParams = new HostParamSet();
    private volatile Frame lastFrame;
    private volatile Frame pendingFrame;
    private volatile byte[] pendingDigest;
    private volatile boolean pendingProbe;
    private Properties installedProperties;

    /**
     * Resolves every dependency method of {@code target} on {@code loader}'s host classes.
     *
     * @throws ReflectiveOperationException when any reviewed getter is absent or mistyped
     */
    public ModelUpdateSkipBridge(final ModelUpdateSkipTarget target, final ClassLoader loader)
            throws ReflectiveOperationException {
        this.target = target;
        modelingViewType = Class.forName(MV, false, loader);
        modelingDocumentType = Class.forName(DOC, false, loader);
        meshFormType = Class.forName(MF, false, loader);
        pathFormType = Class.forName(PF, false, loader);
        final Map<Dep, MethodHandle> handles = resolve(target, loader);
        getParameterSet = handles.get(dep(CM, "getParameterSet"));
        getLastUpdatedParameterSet = handles.get(dep(CM, "getLastUpdatedParameterSet"));
        getAllDrawables = handles.get(dep(CM, "getAllDrawables"));
        getSource = handles.get(dep(CM, "getSource"));
        getParameters = handles.get(dep(PS, "getParameters"));
        getUpdateVersion = handles.get(dep(PS, "getUpdateVersion"));
        paramValue = handles.get(dep(CP, "getValue"));
        paramId = handles.get(dep(CP, "getId"));
        getLastModifiedTime = handles.get(dep(DOC, "getLastModifiedTime"));
        getDoc = handles.get(dep(CX, "getDoc"));
        getDevelopSetting = handles.get(dep(CX, "getDevelopSetting"));
        getAppearanceSetting = handles.get(dep(CX, "getAppearanceSetting"));
        getCurrentEditMode = handles.get(dep(CX, "getCurrentEditMode"));
        isRandomPose = handles.get(dep(MV, "isRandomPoseAnimation"));
        isExternalApp = handles.get(dep(MV, "isExternalAppAnimation"));
        isRecording = handles.get(dep(MV, "isRecording"));
        getCurrentViewMode = handles.get(dep(MV, "getCurrentViewMode"));
        developH = handles.get(dep(target.developSetting(), "h"));
        developK = handles.get(dep(target.developSetting(), "k"));
        appearanceD = handles.get(dep(target.appearanceSetting(), "d"));
        getDraw = handles.get(dep(AS, "getDraw"));
        optimizeArtMesh = handles.get(dep(DS, "getOptimizeArtMesh"));
        optimizeDeformer = handles.get(dep(DS, "getOptimizeDeformer"));
        optimizeDrawOrder = handles.get(dep(DS, "getOptimizeDrawOrder"));
        optimizeHierarchy = handles.get(dep(DS, "getOptimizeHierarchy"));
        getGui = target.appearanceAuxSettings() ? handles.get(dep(AS, "getGui")) : null;
        getWarning = target.appearanceAuxSettings() ? handles.get(dep(GS, "getWarning")) : null;
        maskWarning = target.appearanceAuxSettings() ? handles.get(dep(WARN, "isVisibleMaskWarning")) : null;
        blendWarning = target.appearanceAuxSettings() ? handles.get(dep(WARN, "isBlendModeAppearanceWarning")) : null;
        getCanvas = target.appearanceAuxSettings() ? handles.get(dep(AS, "getCanvas")) : null;
        hideSelected = target.appearanceAuxSettings() ? handles.get(dep(CS, "getHideSelectedState")) : null;
        getDeveloper = target.appearanceAuxSettings() ? handles.get(dep(AS, "getDeveloper")) : null;
        highLightDeformerChild = target.appearanceAuxSettings()
            ? handles.get(dep(DEVSET, "getHighLightDeformerChild")) : null;
        formAnimationGate = handles.get(dep(FT, "a"));
        isModelEditing = handles.get(dep(MS, "isModelEditing"));
        ucA = handles.get(dep(target.updateContext(), "a"));
        ucB = handles.get(dep(target.updateContext(), "b"));
        ucC = handles.get(dep(target.updateContext(), "c"));
        ucD = handles.get(dep(target.updateContext(), "d"));
        ucE = handles.get(dep(target.updateContext(), "e"));
        ucF = handles.get(dep(target.updateContext(), "f"));
        ucG = handles.get(dep(target.updateContext(), "g"));
        ucH = handles.get(dep(target.updateContext(), "h"));
        ucSelection = handles.get(dep(target.updateContext(), "i"));
        ucAliasStack = target.extendedUpdateContext() ? handles.get(dep(target.updateContext(), "l")) : null;
        ucRenderHash = target.extendedUpdateContext() ? handles.get(dep(target.updateContext(), "m")) : null;
        ucRenderCodes = target.extendedUpdateContext() ? handles.get(dep(target.updateContext(), "n")) : null;
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
        updaterA = handles.get(dep(target.owner().replace('/', '.'), "a"));
        updaterB = handles.get(dep(target.owner().replace('/', '.'), "b"));
        appSettingInstance = Class.forName(AS, false, loader).getField("INSTANCE").get(null);
        formAnimationInstance = Class.forName(FT, false, loader).getField("a").get(null);
    }

    private static final String CM = "com.live2d.cubism.doc.model.CModel";
    private static final String PS = "com.live2d.cubism.doc.model.param.CParameterSet";
    private static final String CP = "com.live2d.cubism.doc.model.param.CParameter";
    private static final String CX = "com.live2d.cubism.view.context.CEViewContext";
    private static final String DS = "com.live2d.cubism.setting.AppSetting$DrawSetting";
    private static final String GS = "com.live2d.cubism.setting.AppSetting$GuiSetting";
    private static final String WARN = "com.live2d.cubism.setting.AppSetting$Warning";
    private static final String CS = "com.live2d.cubism.setting.AppSetting$CanvasSetting";
    private static final String DEVSET = "com.live2d.cubism.setting.AppSetting$DeveloperSetting";
    private static final String MS = "com.live2d.cubism.doc.model.CModelSource";
    private static final String DR = "com.live2d.cubism.doc.model.drawable.ACDrawable";
    private static final String PP = "com.live2d.cubism.doc.model.drawable.artPath.CArtPathPoint";
    private static final String SP = "com.live2d.graphics.splineCurve.CSplineCurvePoint";
    private static final String GV = "com.live2d.graphics3d.type.GVector2";

    private Dep dep(final String owner, final String name) {
        for (final Dep d : target.dependencies()) {
            if (d.owner().equals(owner) && d.name().equals(name)) return d;
        }
        throw new IllegalArgumentException("undeclared dependency " + owner + "." + name);
    }

    private static Map<Dep, MethodHandle> resolve(final ModelUpdateSkipTarget target,
                                                  final ClassLoader loader)
            throws ReflectiveOperationException {
        final var lookup = MethodHandles.publicLookup();
        final Map<Dep, MethodHandle> handles = new java.util.HashMap<>();
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

    /** Occupies the callback/after/stats slots; refuses to replace another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("model-update skip already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CALLBACK_PROPERTY) || properties.containsKey(AFTER_PROPERTY)
                || properties.containsKey(STATS_PROPERTY)) {
                throw new IllegalStateException("model-update skip slots occupied");
            }
            try {
                properties.put(CALLBACK_PROPERTY, callback);
                properties.put(AFTER_PROPERTY, afterUpdate);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                properties.remove(CALLBACK_PROPERTY, callback);
                properties.remove(AFTER_PROPERTY, afterUpdate);
                properties.remove(STATS_PROPERTY, statistics);
                throw failure;
            }
        }
    }

    private boolean shouldSkip(final Object[] args) {
        if (!active.get() || !Boolean.getBoolean(ENABLE_PROPERTY)) return false;
        calls.increment();
        final long started = System.nanoTime();
        try {
            final Frame current = readFrame(args);
            pendingFrame = current;
            pendingProbe = false;
            final boolean skip = current != null
                && UnchangedFramePredicate.test(current, lastFrame, currentParams, lastUpdatedParams);
            if (skip && Boolean.getBoolean(PROBE_PROPERTY)) {
                try {
                    pendingDigest = digest(current.model());
                    pendingProbe = true;
                } catch (Throwable failure) {
                    // Undigestable decided-skip frames count as probe mismatches:
                    // the native update still runs and the error is recorded.
                    pendingDigest = null;
                    probeMismatch.increment();
                    writeProbe(current, null, failure);
                }
                return false;
            }
            if (skip) skipped.increment();
            return skip;
        } catch (Throwable failure) {
            failures.increment();
            return false;
        } finally {
            final long nanos = System.nanoTime() - started;
            predicateNanos.add(nanos);
            predicateMaxNanos.accumulateAndGet(nanos, Math::max);
        }
    }

    private void updateCompleted(final Object model) {
        try {
            full.increment();
            final Frame completed = pendingFrame;
            if (completed != null && completed.model() == model) lastFrame = completed;
            if (pendingProbe) {
                pendingProbe = false;
                try {
                    final byte[] post = digest(model);
                    if (!Arrays.equals(post, pendingDigest)) {
                        probeMismatch.increment();
                        writeProbe(completed, post, null);
                    }
                } catch (Throwable failure) {
                    probeMismatch.increment();
                    writeProbe(completed, null, failure);
                }
            }
        } catch (Throwable failure) {
            failures.increment();
        }
    }

    private Frame readFrame(final Object[] args) throws Throwable {
        if (args == null || args.length < 7) return null;
        final Object ctx = args[1], model = args[2], update = args[4], contextParam = args[6];
        final Object view = modelingViewType.isInstance(ctx) ? ctx : null;
        Object document = null;
        long documentModified = Long.MIN_VALUE;
        if (ctx != null) {
            final Object doc = (Object) getDoc.invokeExact(ctx);
            if (modelingDocumentType.isInstance(doc)) {
                document = doc;
                documentModified = (long) getLastModifiedTime.invokeExact(doc);
            }
        }
        Object viewMode = null, editMode = null;
        float appearance = 0f;
        boolean randomPose = false, externalApp = false, recording = false;
        if (view != null) {
            viewMode = (Object) getCurrentViewMode.invokeExact(view);
            editMode = (Object) getCurrentEditMode.invokeExact(view);
            final Object setting = (Object) getAppearanceSetting.invokeExact(view);
            if (setting != null) appearance = (float) appearanceD.invokeExact(setting);
            randomPose = (boolean) isRandomPose.invokeExact(view);
            externalApp = (boolean) isExternalApp.invokeExact(view);
            recording = (boolean) isRecording.invokeExact(view);
        }
        boolean developHValue = false, developKValue = false;
        if (ctx != null) {
            final Object setting = (Object) getDevelopSetting.invokeExact(ctx);
            if (setting != null) {
                developHValue = (boolean) developH.invokeExact(setting);
                developKValue = (boolean) developK.invokeExact(setting);
            }
        }
        boolean optArtMesh = false, optDeformer = false, optDrawOrder = false, optHierarchy = false;
        boolean maskWarn = false, blendWarn = false, hideSel = false, highLight = false;
        final Object draw = (Object) getDraw.invokeExact(appSettingInstance);
        if (draw != null) {
            optArtMesh = (boolean) optimizeArtMesh.invokeExact(draw);
            optDeformer = (boolean) optimizeDeformer.invokeExact(draw);
            optDrawOrder = (boolean) optimizeDrawOrder.invokeExact(draw);
            optHierarchy = (boolean) optimizeHierarchy.invokeExact(draw);
        }
        if (getGui != null) {
            final Object gui = (Object) getGui.invokeExact(appSettingInstance);
            final Object warn = gui != null ? (Object) getWarning.invokeExact(gui) : null;
            if (warn != null) {
                maskWarn = (boolean) maskWarning.invokeExact(warn);
                blendWarn = (boolean) blendWarning.invokeExact(warn);
            }
            final Object canvas = (Object) getCanvas.invokeExact(appSettingInstance);
            if (canvas != null) hideSel = (boolean) hideSelected.invokeExact(canvas);
            final Object developer = (Object) getDeveloper.invokeExact(appSettingInstance);
            if (developer != null) {
                highLight = (boolean) highLightDeformerChild.invokeExact(developer);
            }
        }
        final boolean formGate = ctx != null
            && (boolean) formAnimationGate.invokeExact(formAnimationInstance, ctx);
        final Object source = model != null ? (Object) getSource.invokeExact(model) : null;
        final boolean editing = source != null && (boolean) isModelEditing.invokeExact(source);
        boolean ucPresent = update != null, ucAValue = false, ucBValue = false, ucDValue = false,
            ucEValue = false, ucFValue = false, stacksEmpty = true;
        float ucCValue = 0f;
        Object ucView = null, ucEdit = null, ucHash = null;
        List<Object> selection = null;
        if (update != null) {
            ucAValue = (boolean) ucA.invokeExact(update);
            ucBValue = (boolean) ucB.invokeExact(update);
            ucCValue = (float) ucC.invokeExact(update);
            ucDValue = (boolean) ucD.invokeExact(update);
            ucEValue = (boolean) ucE.invokeExact(update);
            ucFValue = (boolean) ucF.invokeExact(update);
            ucView = (Object) ucG.invokeExact(update);
            ucEdit = (Object) ucH.invokeExact(update);
            if (view != null) {
                selection = castParameters((Object) ucSelection.invokeExact(update));
            }
            if (ucAliasStack != null) {
                ucHash = (Object) ucRenderHash.invokeExact(update);
                final Object stack = (Object) ucAliasStack.invokeExact(update);
                final Object codes = (Object) ucRenderCodes.invokeExact(update);
                stacksEmpty = (stack == null || ((List<?>) stack).isEmpty())
                    && (codes == null || ((List<?>) codes).isEmpty());
            }
        }
        final Object parameterSet = model != null ? (Object) getParameterSet.invokeExact(model) : null;
        final Object lastUpdated = model != null ? (Object) getLastUpdatedParameterSet.invokeExact(model) : null;
        currentParams.list = parameterSet != null
            ? castParameters((Object) getParameters.invokeExact(parameterSet)) : null;
        lastUpdatedParams.list = lastUpdated != null
            ? castParameters((Object) getParameters.invokeExact(lastUpdated)) : null;
        final int paramVersion = parameterSet != null
            ? (int) getUpdateVersion.invokeExact(parameterSet) : Integer.MIN_VALUE;
        final boolean conflict = args.length > 7 && Boolean.TRUE.equals(args[7]);
        final Object system = args[0];
        final boolean flagA = system != null && (boolean) updaterA.invokeExact(system);
        final boolean flagB = system != null && (boolean) updaterB.invokeExact(system);
        return new Frame(model, view, document, documentModified, paramVersion,
            viewMode, editMode, appearance,
            optArtMesh, optDeformer, optDrawOrder, optHierarchy,
            maskWarn, blendWarn, hideSel, highLight,
            randomPose, externalApp, recording, developHValue, developKValue, formGate, editing,
            flagA, flagB,
            ucPresent, ucAValue, ucBValue, ucCValue, ucDValue, ucEValue, ucFValue,
            ucView, ucEdit, selection, ucHash, stacksEmpty, conflict, contextParam,
            Boolean.TRUE.equals(args[3]), Boolean.TRUE.equals(args[5]));
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castParameters(final Object parameters) {
        return parameters instanceof List ? (List<Object>) parameters : null;
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

    private void writeProbe(final Frame frame, final byte[] post, final Throwable failure) {
        try {
            final String path = System.getProperty(RESULT_PROPERTY);
            if (path == null || path.isBlank()) return;
            final StringBuilder json = new StringBuilder(512).append('{');
            json.append("\"target\":\"").append(target.version()).append('\"');
            json.append(",\"docLastModified\":").append(frame == null ? "null" : frame.documentLastModified());
            json.append(",\"paramSetUpdateVersion\":").append(frame == null ? "null" : frame.parameterSetUpdateVersion());
            if (post != null) json.append(",\"postDigest\":\"").append(HexFormat.of().formatHex(post)).append('\"');
            if (failure != null) json.append(",\"error\":\"").append(escape(describe(failure))).append('\"');
            if (frame != null) {
                json.append(",\"flags\":{");
                json.append("\"modelEditing\":").append(frame.modelEditing());
                json.append(",\"randomPose\":").append(frame.randomPoseAnimation());
                json.append(",\"externalApp\":").append(frame.externalAppAnimation());
                json.append(",\"recording\":").append(frame.recording());
                json.append(",\"developH\":").append(frame.developSettingH());
                json.append(",\"developK\":").append(frame.developSettingK());
                json.append(",\"formGate\":").append(frame.formAnimationGate());
                json.append(",\"updateContextA\":").append(frame.updateContextA());
                json.append(",\"contextParamNull\":").append(frame.contextParam() == null);
                json.append(",\"stacksEmpty\":").append(frame.axStacksEmpty());
                json.append(",\"conflict\":").append(frame.conflictPolygon());
                json.append('}');
            }
            json.append("}\n");
            Files.writeString(Path.of(path), json.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            failures.increment();
        }
    }

    /** Exception class/message plus the top bridge frames, for offline diagnosis. */
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
        return Map.of("active", active.get() ? 1L : 0L, "calls", calls.sum(),
            "skipped", skipped.sum(), "full", full.sum(),
            "probeMismatch", probeMismatch.sum(), "failures", failures.sum(),
            "predicateNanos", predicateNanos.sum(), "predicateMaxNanos", predicateMaxNanos.get(),
            "digestNanos", digestNanos.sum());
    }

    /** Clears owned slots; outstanding callbacks fall back to the native path. */
    @Override public synchronized void close() {
        active.set(false);
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) synchronized (properties) {
            properties.remove(CALLBACK_PROPERTY, callback);
            properties.remove(AFTER_PROPERTY, afterUpdate);
            properties.remove(STATS_PROPERTY, statistics);
        }
    }

    private final class HostParamSet implements ParamSet {
        private List<Object> list;
        @Override public int size() { return list == null ? -1 : list.size(); }
        @Override public Object idAt(final int index) {
            try { return (Object) paramId.invokeExact(list.get(index)); }
            catch (Throwable failure) { throw new IllegalStateException(failure); }
        }
        @Override public float valueAt(final int index) {
            try { return (float) paramValue.invokeExact(list.get(index)); }
            catch (Throwable failure) { throw new IllegalStateException(failure); }
        }
    }
}
