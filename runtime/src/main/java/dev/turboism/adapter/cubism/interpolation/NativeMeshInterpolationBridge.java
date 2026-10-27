package dev.turboism.adapter.cubism.interpolation;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Internal entrypoint for the pinned multi-version interpolation hook. No host dependency. */
public final class NativeMeshInterpolationBridge {
    /**
     * Exact reviewed host artifacts admitted by the interpolation hook.
     */
    public static final java.util.Set<String> HOST_SHA256S = ReviewedHostArtifacts.reviewedSha256s();

    private static final HostArtifactDigestCache FINGERPRINTS = new HostArtifactDigestCache();

    static dev.turboism.mapping.verification.HostArtifactDigest hostArtifactDigest(final Path artifact)
            throws java.io.IOException {
        return FINGERPRINTS.digest(artifact);
    }

    static boolean isAdmittedHostArtifact(final Path artifact) {
        try {
            requireAdmittedHostArtifact(artifact);
            return true;
        } catch (java.io.IOException | IllegalArgumentException refused) {
            return false;
        }
    }

    static void requireAdmittedHostArtifact(final Path artifact) throws java.io.IOException {
        if (!ReviewedHostArtifacts.isReviewed(hostArtifactDigest(artifact))) {
            throw new IllegalArgumentException("unreviewed host artifact");
        }
    }

    static final String EXTENSION =
            "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension";
    private static volatile boolean enabled;
    private static final InterpolationCurveCache CURVES = new InterpolationCurveCache();
    private static final LocalInterpolationCache LOCAL = new LocalInterpolationCache();
    private static final AtomicLong CACHE_DOMAINS = new AtomicLong();
    private static volatile boolean coefficientDefault;
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong DIRECT_UPDATE_BINDINGS = new AtomicLong();
    private static final ClassValue<Optional<Access>> ACCESS = new ClassValue<>() {
        @Override
        protected Optional<Access> computeValue(Class<?> type) {
            try {
                return Optional.of(new Access(type));
            } catch (ReflectiveOperationException
                    | java.io.IOException
                    | java.net.URISyntaxException
                    | RuntimeException
                    | LinkageError unsupported) {
                return Optional.empty();
            }
        }
    };

    private NativeMeshInterpolationBridge() {}

    /** Installer lifecycle switch. Disabling leaves transformed methods on the official path. */
    public static void enabled(boolean value) {
        enabled = value;
        CURVES.enabled(value);
        LOCAL.enabled(value);
    }
    /**
     * @return calls that completed a verified ArtMesh, Warp or Rotation interpolation path
     */
    public static long acceptedCalls() {
        return ACCEPTED.get();
    }
    /**
     * @return calls declined back to the official interpolation
     */
    public static long refusedCalls() {
        return REFUSED.get();
    }
    /**
     * @return bindings resolved without copying the official list
     */
    public static long directUpdateBindings() {
        return DIRECT_UPDATE_BINDINGS.get();
    }

    /** Detached numerical cache observations; no native form storage is bounded or evicted. */
    public record CurveCacheStatistics(
            long hits, long misses, long evictions, int entries, long bytes, long byteBudget) {}

    public static CurveCacheStatistics curveCacheStatistics() {
        var stats = CURVES.statistics();
        return new CurveCacheStatistics(
                stats.hits(), stats.misses(), stats.evictions(), stats.entries(), stats.bytes(), stats.byteBudget());
    }

    /** Detached local-refresh observations; default product opt-in remains unchanged. */
    public static CurveCacheStatistics localRefreshStatistics() {
        var s = LOCAL.statistics();
        return new CurveCacheStatistics(s.hits(), s.misses(), s.evictions(), s.entries(), s.bytes(), s.byteBudget());
    }

    static boolean isEnabled() {
        return enabled;
    }

    static boolean coefficientsEnabled() {
        return Boolean.parseBoolean(
                System.getProperty("turboism.meshInterpolation.coefficients", Boolean.toString(coefficientDefault)));
    }

    static void coefficientDefault(boolean value) {
        coefficientDefault = value;
    }

    /** Returns false only before mutation. Never rerun the official method after a partial write. */
    public static boolean tryUpdate(
            Object extension, List<?> target, List<?> source, List<?> keys, Object type, float scale) {
        if (!enabled || extension == null) return false;
        Optional<Access> access = ACCESS.get(extension.getClass());
        if (access.isEmpty()) {
            REFUSED.incrementAndGet();
            return false;
        }
        try {
            boolean applied = access.get().update(extension, target, source, keys, type, scale);
            if (applied) ACCEPTED.incrementAndGet();
            else REFUSED.incrementAndGet();
            return applied;
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("Native mesh interpolation failed after verified binding", failure);
        }
    }

    /** Used only by the pinned firstOrNull precheck, never by full-bank readers.
     * Avoid materializing a concatenation when only its first element is needed.
     * Disabled installations preserve the native full-list construction. */
    public static List<?> firstInspectionBank(List<?> normal, List<?> morph) {
        if (enabled) return normal.isEmpty() ? morph : normal;
        var combined = new ArrayList<Object>(normal.size() + morph.size());
        combined.addAll(normal);
        combined.addAll(morph);
        return combined;
    }

    private static final class Access {
        final long cacheDomain = CACHE_DOMAINS.incrementAndGet();
        final Class<?> keyClass;
        final FormAccess meshForm, warpForm, rotationForm;
        final Object ellipse, sns;
        final MethodHandle inserted, value, interval, fraction;
        final MethodHandle vector, vectorSet, vectorX, vectorY, calculator, initialize, evaluate;
        final MethodHandle radians, morphSources, morphExtended;

        Access(Class<?> extension)
                throws ReflectiveOperationException, java.io.IOException, java.net.URISyntaxException {
            if (!extension.getName().equals(EXTENSION)) throw new IllegalArgumentException("extension type");
            Path artifact = Path.of(extension
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toRealPath();
            requireAdmittedHostArtifact(artifact);
            ClassLoader loader = extension.getClassLoader();
            Class<?> blendable = host("com.live2d.core.IBlendable", loader, artifact);
            meshForm = new FormAccess(
                    host("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", loader, artifact), blendable);
            warpForm = new FormAccess(
                    host("com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm", loader, artifact), blendable);
            rotationForm = new FormAccess(
                    host("com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm", loader, artifact),
                    blendable);
            morphSources = MethodHandles.publicLookup()
                    .unreflect(
                            meshForm.type.getMethod("getSource").getReturnType().getMethod("getKeyformMorphTargets"))
                    .asType(MethodType.methodType(List.class, Object.class));
            morphExtended = MethodHandles.publicLookup()
                    .unreflect(extension.getMethod("getMorphTargetExtendedKeyforms"))
                    .asType(MethodType.methodType(List.class, Object.class));
            keyClass = host(EXTENSION + "$b", loader, artifact);
            Class<?> typeClass = host(
                    "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType",
                    loader,
                    artifact);
            Class<?> vectorClass = host("com.live2d.graphics3d.type.GVector2", loader, artifact);
            Class<?> calculatorClass =
                    host("com.live2d.cubism.doc.model.interpolator.extendedInterpolation.a", loader, artifact);
            Class<?> constants = host("jp.noids.util.ap", loader, artifact);
            radians = MethodHandles.publicLookup().findStaticGetter(constants, "c", double.class);
            ellipse = typeClass.getField("ELLIPSE").get(null);
            sns = typeClass.getField("SNS_CURVE").get(null);
            inserted = method(keyClass, "b", boolean.class, new Class<?>[0], Object.class);
            value = method(keyClass, "a", float.class, new Class<?>[0], Object.class);
            interval = method(keyClass, "c", int.class, new Class<?>[0], Object.class);
            fraction = method(keyClass, "d", float.class, new Class<?>[0], Object.class);
            vector = MethodHandles.publicLookup()
                    .findConstructor(vectorClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            vectorSet = method(
                    vectorClass,
                    "set",
                    void.class,
                    new Class<?>[] {float.class, float.class},
                    Object.class,
                    float.class,
                    float.class);
            vectorX = method(vectorClass, "getX", float.class, new Class<?>[0], Object.class);
            vectorY = method(vectorClass, "getY", float.class, new Class<?>[0], Object.class);
            calculator = method(typeClass, "b", Object.class, new Class<?>[0], Object.class);
            initialize = method(
                    calculatorClass,
                    "a",
                    void.class,
                    new Class<?>[] {List.class, boolean.class, List.class, List.class},
                    Object.class,
                    List.class,
                    boolean.class,
                    List.class,
                    List.class);
            evaluate = method(
                    calculatorClass,
                    "a",
                    Object.class,
                    new Class<?>[] {int.class, float.class, vectorClass},
                    Object.class,
                    int.class,
                    float.class,
                    Object.class);
        }

        /** Each admitted form keeps its native blend and per-point update semantics.
         * WarpPointRef and MeshPointRef both apply old*(1-weight)+new*weight,
         * then setUpdated once per point; no grid/source mutation is added. */
        private static final class FormAccess {
            final Class<?> type;
            final MethodHandle positions, owner, zero, blend, postBlend, updated;
            final MethodHandle originX, originY, setOriginX, setOriginY;
            final boolean rotation;
            final LocalFormState local;
            final java.util.function.Consumer<Object> directUpdated;

            FormAccess(Class<?> type, Class<?> blendable) throws ReflectiveOperationException {
                this.type = type;
                local = LocalFormState.bind(type);
                rotation = type.getName().endsWith(".CRotationDeformerForm");
                positions =
                        rotation ? null : method(type, "getPositions", float[].class, new Class<?>[0], Object.class);
                originX = rotation ? method(type, "getOriginX", float.class, new Class<?>[0], Object.class) : null;
                originY = rotation ? method(type, "getOriginY", float.class, new Class<?>[0], Object.class) : null;
                setOriginX = rotation
                        ? method(
                                type, "setOriginX", void.class, new Class<?>[] {float.class}, Object.class, float.class)
                        : null;
                setOriginY = rotation
                        ? method(
                                type, "setOriginY", void.class, new Class<?>[] {float.class}, Object.class, float.class)
                        : null;
                owner = method(type, "getSource", Object.class, new Class<?>[0], Object.class);
                zero = method(type, "zero", void.class, new Class<?>[0], Object.class);
                blend = method(
                        type,
                        "blend",
                        void.class,
                        new Class<?>[] {blendable, float.class},
                        Object.class,
                        Object.class,
                        float.class);
                postBlend = method(type, "postBlend", void.class, new Class<?>[0], Object.class);
                updated = method(type, "setUpdated", void.class, new Class<?>[0], Object.class);
                directUpdated = directUpdate(type);
            }

            float[] readPositions(Object form) throws Throwable {
                return rotation
                        ? new float[] {(float) originX.invokeExact(form), (float) originY.invokeExact(form)}
                        : (float[]) positions.invokeExact(form);
            }

            void writeRotation(Object form, float x, float y) throws Throwable {
                // Native RotationDeformerPointRef.moveToOnLocal: setters have no
                // hidden side effects; setUpdated is invoked separately once.
                setOriginX.invokeExact(form, (float) originX.invokeExact(form) * 0f + x * 1f);
                setOriginY.invokeExact(form, (float) originY.invokeExact(form) * 0f + y * 1f);
            }
        }

        @SuppressWarnings("unchecked")
        static java.util.function.Consumer<Object> directUpdate(Class<?> form) {
            try {
                // The lambda retains the exact official per-point setter; only dispatch changes.
                var lookup = MethodHandles.lookup();
                var implementation = lookup.unreflect(form.getMethod("setUpdated"));
                var site = java.lang.invoke.LambdaMetafactory.metafactory(
                        lookup,
                        "accept",
                        MethodType.methodType(java.util.function.Consumer.class),
                        MethodType.methodType(void.class, Object.class),
                        implementation,
                        MethodType.methodType(void.class, form));
                java.util.function.Consumer<Object> bound =
                        (java.util.function.Consumer<Object>) site.getTarget().invokeExact();
                DIRECT_UPDATE_BINDINGS.incrementAndGet();
                return bound;
            } catch (Throwable unsupported) {
                FatalErrors.rethrowIfFatal(unsupported);
                return null;
            }
        }

        static Class<?> host(String name, ClassLoader loader, Path artifact)
                throws ReflectiveOperationException, java.io.IOException, java.net.URISyntaxException {
            Class<?> type = Class.forName(name, false, loader);
            if (type.getClassLoader() != loader
                    || !Path.of(type.getProtectionDomain()
                                    .getCodeSource()
                                    .getLocation()
                                    .toURI())
                            .toRealPath()
                            .equals(artifact)) throw new IllegalArgumentException("mixed host definition " + name);
            return type;
        }

        static MethodHandle method(
                Class<?> owner, String name, Class<?> result, Class<?>[] parameters, Class<?>... erased)
                throws ReflectiveOperationException {
            return MethodHandles.publicLookup()
                    .unreflect(owner.getMethod(name, parameters))
                    .asType(MethodType.methodType(result, erased));
        }

        boolean update(Object extension, List<?> target, List<?> source, List<?> keys, Object type, float scale)
                throws Throwable {
            if ((type != ellipse && type != sns)
                    || target == null
                    || source == null
                    || keys == null
                    || target.isEmpty()
                    || source.size() < 2
                    || keys.size() != target.size()
                    || !Float.isFinite(scale)) return false;
            int count = target.size(), size = -1, anchors = 0;
            boolean[] extra = new boolean[count];
            int[] intervals = new int[count], right = new int[count];
            float[] weights = new float[count];
            var knots = new ArrayList<Float>(source.size());
            for (int i = 0; i < count; i++) {
                Object key = keys.get(i);
                if (key == null || key.getClass() != keyClass) return false;
                extra[i] = (boolean) inserted.invokeExact(key);
                float knot = (float) value.invokeExact(key) * scale;
                if (!Float.isFinite(knot)) return false;
                if (!extra[i]) {
                    knots.add(knot);
                    anchors++;
                } else {
                    intervals[i] = (int) interval.invokeExact(key);
                    weights[i] = (float) fraction.invokeExact(key);
                    if (intervals[i] < 0 || intervals[i] >= source.size() - 1 || !Float.isFinite(weights[i]))
                        return false;
                }
            }
            if (extra[0] || extra[count - 1] || anchors != source.size()) return false;
            Object first = target.get(0);
            if (first == null) return false;
            FormAccess formAccess = first.getClass() == meshForm.type
                    ? meshForm
                    : first.getClass() == warpForm.type
                            ? warpForm
                            : first.getClass() == rotationForm.type ? rotationForm : null;
            if (formAccess == null) return false;
            Class<?> formClass = formAccess.type;
            MethodHandle owner = formAccess.owner,
                    zero = formAccess.zero,
                    blend = formAccess.blend,
                    postBlend = formAccess.postBlend,
                    updated = formAccess.updated;
            var directUpdated = formAccess.directUpdated;
            Object formOwner = owner.invokeExact(first);
            if (formOwner == null) return false;
            float[][] dst = new float[count][], src = new float[source.size()][];
            var targetArrays = new IdentityHashMap<Object, Boolean>();
            for (int i = 0; i < count; i++) {
                Object form = target.get(i);
                if (form == null || form.getClass() != formClass || owner.invokeExact(form) != formOwner) return false;
                dst[i] = formAccess.readPositions(form);
                if (dst[i] == null) return false;
                if (size < 0) size = dst[i].length;
                if (dst[i].length != size || size == 0 || size % 2 != 0) return false;
                // Non-finite prior targets can carry JVM-dependent NaN payloads through
                // native zero/blend/setter arithmetic. Decline before any write.
                for (float coordinate : dst[i]) if (!Float.isFinite(coordinate)) return false;
                Boolean prior = targetArrays.putIfAbsent(formAccess.rotation ? form : dst[i], extra[i]);
                if (prior != null && (extra[i] || prior)) return false;
            }
            for (int i = 0; i < src.length; i++) {
                Object form = source.get(i);
                if (form == null || form.getClass() != formClass || owner.invokeExact(form) != formOwner) return false;
                src[i] = formAccess.readPositions(form);
                if (src[i] == null || src[i].length != size) return false;
                for (float coordinate : src[i]) if (!Float.isFinite(coordinate)) return false;
                if (Boolean.TRUE.equals(targetArrays.get(formAccess.rotation ? form : src[i]))) return false;
            }
            int next = count - 1;
            for (int i = count - 1; i >= 0; i--) {
                if (!extra[i]) next = i;
                right[i] = next;
            }
            boolean coefficientMode = type == ellipse && coefficientsEnabled();
            double angleScale = type == ellipse ? (double) radians.invokeExact() : 0d;
            boolean localEnabled =
                    Boolean.parseBoolean(System.getProperty("turboism.meshInterpolation.localRefresh", "true"))
                            && formAccess.local != null
                            && ((List<?>) morphSources.invokeExact(formOwner)).isEmpty()
                            && ((List<?>) morphExtended.invokeExact(extension)).isEmpty();
            int[] geometry = geometryInput(
                    type == sns,
                    coefficientMode,
                    angleScale,
                    size,
                    src,
                    knots,
                    extra,
                    intervals,
                    weights,
                    localEnabled);
            long curveBytes =
                    geometry == null ? Long.MAX_VALUE : 256L + 4L * geometry.length + (long) count * size * 9 / 2;
            InterpolationCurveCache.Key curveKey = geometry != null
                            && size >= 16
                            && curveBytes <= InterpolationCurveCache.MAX_ENTRY_BYTES
                            && Boolean.parseBoolean(System.getProperty("turboism.meshInterpolation.curveCache", "true"))
                    ? new InterpolationCurveCache.Key(cacheDomain, geometry)
                    : null;
            InterpolationCurveCache.Result cached = curveKey == null ? null : CURVES.get(curveKey);
            // Source-only frequency gate is detached and separately bounded, so a large
            // multi-axis sweep cannot evict its own admission history from the curve LRU.
            boolean localAdmitted = localEnabled
                    && geometry != null
                    && !(formAccess.rotation && type == sns)
                    && LOCAL.admit(
                            curveKey != null ? curveKey : InterpolationCurveCache.Key.owned(cacheDomain, geometry));
            LocalInterpolationState local = localAdmitted
                    ? LocalInterpolationState.create(
                            LOCAL, cacheDomain, geometry, formAccess.local, target, source, dst)
                    : null;
            try {
                boolean[] notifications = local == null ? null : LOCAL.get(local.key);
                if (notifications != null) {
                    // Native order and actual successful-write filtering, not a uniform version increment.
                    for (int point = 0; point < size / 2; point++)
                        for (int i = 0; i < count; i++)
                            if (notifications[point * count + i]) updated.invokeExact(target.get(i));
                    return true;
                }
                if (local != null) local.capture(LOCAL);
                InterpolationCurveCache.Result capture = curveKey != null && cached == null && CURVES.admit(curveKey)
                        ? new InterpolationCurveCache.Result(count * size)
                        : null;
                List<Object> vectors = new ArrayList<>(cached == null ? src.length : 0);
                Object output = null;
                EllipseWeights[] coefficients = null;
                if (cached == null) {
                    for (int i = 0; i < src.length; i++) vectors.add(vector.invokeExact());
                    output = vector.invokeExact();
                    if (coefficientMode) {
                        float[] angles = new float[knots.size()];
                        for (int i = 0; i < angles.length; i++) angles[i] = knots.get(i);
                        coefficients = new EllipseWeights[count];
                        for (int i = 0; i < count; i++)
                            if (extra[i])
                                coefficients[i] = EllipseWeights.prepare(angles, intervals[i], weights[i], angleScale);
                    }
                }
                boolean directUpdates = coefficientMode
                        && directUpdated != null
                        && Boolean.getBoolean("turboism.meshInterpolation.directUpdates");
                // Every refusal and handle binding finishes above this mutation boundary.
                Object left = first;
                for (int i = 1; i < count; i++) {
                    Object form = target.get(i);
                    if (!extra[i]) {
                        left = form;
                        continue;
                    }
                    zero.invokeExact(form);
                    blend.invokeExact(form, left, 1f - weights[i]);
                    blend.invokeExact(form, target.get(right[i]), weights[i]);
                    postBlend.invokeExact(form);
                }
                if (cached != null) {
                    // Replay every actual point write in native order. Blending above and
                    // old*0+new*1 below are retained, including signed-zero/NaN behavior.
                    for (int offset = 0; offset < size; offset += 2)
                        for (int i = 0; i < count; i++)
                            if (extra[i] && cached.present(i * size + offset)) {
                                float x = cached.get(i * size + offset), y = cached.get(i * size + offset + 1);
                                if (formAccess.rotation) formAccess.writeRotation(target.get(i), x, y);
                                else {
                                    dst[i][offset] = dst[i][offset] * 0f + x * 1f;
                                    dst[i][offset + 1] = dst[i][offset + 1] * 0f + y * 1f;
                                }
                                if (local != null) local.actualWrites[(offset / 2) * count + i] = true;
                                if (directUpdates) directUpdated.accept(target.get(i));
                                else updated.invokeExact(target.get(i));
                            }
                    if (local != null) local.completed(LOCAL, target, dst);
                    return true;
                }
                // A fresh SNS calculator is owned by this call and only initialized as
                // open/no-corner. Native initialize replaces its point/spline banks;
                // sharing with closed curves would retain native corner-index state.
                Object snsCurve = type == sns ? calculator.invokeExact(type) : null;
                for (int offset = 0; offset < size; offset += 2) {
                    if (coefficients != null) {
                        for (int i = 0; i < count; i++)
                            if (extra[i]) {
                                float x = coefficients[i].evaluate(src, offset),
                                        y = coefficients[i].evaluate(src, offset + 1);
                                if (capture != null) capture.set(i * size + offset, x, y);
                                if (formAccess.rotation) formAccess.writeRotation(target.get(i), x, y);
                                else {
                                    dst[i][offset] = dst[i][offset] * 0f + x * 1f;
                                    dst[i][offset + 1] = dst[i][offset + 1] * 0f + y * 1f;
                                }
                                if (local != null) local.actualWrites[(offset / 2) * count + i] = true;
                                if (directUpdates) directUpdated.accept(target.get(i));
                                else updated.invokeExact(target.get(i));
                            }
                        continue;
                    }
                    for (int i = 0; i < src.length; i++)
                        vectorSet.invokeExact(vectors.get(i), src[i][offset], src[i][offset + 1]);
                    Object curve = type == sns ? snsCurve : calculator.invokeExact(type);
                    initialize.invokeExact(curve, (List<?>) vectors, true, (List<?>) null, (List<?>) knots);
                    for (int i = 0; i < count; i++)
                        if (extra[i]) {
                            Object result = evaluate.invokeExact(curve, intervals[i], weights[i], output);
                            if (result != null) {
                                float x = (float) vectorX.invokeExact(result), y = (float) vectorY.invokeExact(result);
                                if (capture != null) capture.set(i * size + offset, x, y);
                                if (formAccess.rotation) formAccess.writeRotation(target.get(i), x, y);
                                else {
                                    dst[i][offset] = dst[i][offset] * 0f + x * 1f;
                                    dst[i][offset + 1] = dst[i][offset + 1] * 0f + y * 1f;
                                }
                                if (local != null) local.actualWrites[(offset / 2) * count + i] = true;
                                updated.invokeExact(target.get(i));
                            }
                        }
                }
                if (capture != null) CURVES.put(curveKey, capture);
                if (local != null) local.completed(LOCAL, target, dst);
                return true;
            } finally {
                if (local != null) local.release(LOCAL);
            }
        }

        private int[] geometryInput(
                boolean snsMode,
                boolean coefficientMode,
                double angleScale,
                int size,
                float[][] src,
                List<Float> knots,
                boolean[] extra,
                int[] intervals,
                float[] weights,
                boolean localEnabled) {
            if (!localEnabled
                    && (size < 16
                            || !Boolean.parseBoolean(
                                    System.getProperty("turboism.meshInterpolation.curveCache", "true")))) return null;
            long coordinates = (long) extra.length * size;
            long words = 8L + (long) src.length * size + knots.size() + 3L * extra.length;
            long retained = 256L + 4L * words + 4L * coordinates + coordinates / 2;
            if (retained > InterpolationCurveCache.MAX_ENTRY_BYTES && !localEnabled) return null;
            if (4L * words + 256L > LocalInterpolationCache.MAX_ENTRY_BYTES) return null;
            int[] signature = new int[(int) words];
            int at = 0;
            signature[at++] = snsMode ? 1 : 0;
            signature[at++] = coefficientMode ? 1 : 0;
            long angleBits = Double.doubleToRawLongBits(angleScale);
            signature[at++] = (int) (angleBits >>> 32);
            signature[at++] = (int) angleBits;
            signature[at++] = size;
            signature[at++] = src.length;
            signature[at++] = extra.length;
            signature[at++] = knots.size();
            for (float[] coordinatesOfSource : src)
                for (float coordinate : coordinatesOfSource) signature[at++] = Float.floatToRawIntBits(coordinate);
            for (float knot : knots) signature[at++] = Float.floatToRawIntBits(knot);
            for (int i = 0; i < extra.length; i++) {
                signature[at++] = extra[i] ? 1 : 0;
                signature[at++] = intervals[i];
                signature[at++] = Float.floatToRawIntBits(weights[i]);
            }
            return signature;
        }
    }
}
