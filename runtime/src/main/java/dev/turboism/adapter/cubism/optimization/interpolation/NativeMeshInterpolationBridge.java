package dev.turboism.adapter.cubism.optimization.interpolation;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Internal entrypoint for the pinned multi-version interpolation hook. No host dependency. */
public final class NativeMeshInterpolationBridge {
    /**
     * Exact reviewed host artifacts admitted by the interpolation hook.
     */
    public static final java.util.Set<String> HOST_SHA256S = ReviewedHostArtifacts.reviewedSha256s();

    static dev.turboism.mapping.verification.HostArtifactDigest hostArtifactDigest(final Path artifact)
            throws java.io.IOException {
        return dev.turboism.mapping.verification.HostArtifactDigest.cached(artifact);
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
    private static final String PROP_COEFFICIENTS = "turboism.meshInterpolation.coefficients";
    private static final String PROP_LOCAL_REFRESH = "turboism.meshInterpolation.localRefresh";
    private static final String PROP_CURVE_CACHE = "turboism.meshInterpolation.curveCache";
    private static final String PROP_DIRECT_UPDATES = "turboism.meshInterpolation.directUpdates";
    private static final String DEFAULT_ENABLED = "true";
    private static final int MIN_SOURCE_KEYFORMS = 2;
    private static final int COORDINATE_STRIDE = 2;
    private static final int MIN_CURVE_CACHE_COORDINATES = 16;
    private static final int NO_GEOMETRY = -1;
    private static final int INITIAL_SLOT_CAPACITY = 16;
    private static final int MAX_RETAINED_SLOTS = 256;
    private static final int MAX_RETAINED_GEOMETRY_WORDS = 65_536;
    private static final int DOUBLE_HIGH_WORD_SHIFT = 32;
    private static final long GEOMETRY_HEADER_WORDS = 8L;
    private static final long EXTRA_DESCRIPTOR_WORDS = 3L;
    private static final long ENTRY_OVERHEAD_BYTES = 256L;
    private static final long INT_BYTES = 4L;
    private static final long RESULT_BYTES_NUMERATOR = 9L;
    private static final long RESULT_BYTES_DENOMINATOR = 2L;
    private static volatile boolean enabled;
    private static final InterpolationCurveCache CURVES = new InterpolationCurveCache();
    private static final LocalInterpolationCache LOCAL = new LocalInterpolationCache();
    private static final AtomicLong CACHE_DOMAINS = new AtomicLong();
    private static volatile boolean coefficientDefault;
    private static final AtomicLong ACCEPTED = new AtomicLong();
    private static final AtomicLong REFUSED = new AtomicLong();
    private static final AtomicLong DIRECT_UPDATE_BINDINGS = new AtomicLong();
    private static final ThreadLocal<UpdateScratch> UPDATE_SCRATCH = ThreadLocal.withInitial(UpdateScratch::new);
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

    /** Returns detached observations of the bounded numerical interpolation cache. */
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
        return Boolean.parseBoolean(System.getProperty(PROP_COEFFICIENTS, Boolean.toString(coefficientDefault)));
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

    @FunctionalInterface
    private interface VectorSetter {
        void set(Object vector, float x, float y);
    }

    @FunctionalInterface
    private interface FloatGetter {
        float get(Object target);
    }

    /** Per-thread reusable scratch arrays for {@link Access#update}; never retains host references after return. */
    private static final class UpdateScratch {
        boolean[] extra = new boolean[INITIAL_SLOT_CAPACITY];
        int[] intervals = new int[INITIAL_SLOT_CAPACITY];
        int[] right = new int[INITIAL_SLOT_CAPACITY];
        int[] extraIndices = new int[INITIAL_SLOT_CAPACITY];
        float[] weights = new float[INITIAL_SLOT_CAPACITY];
        float[] knotValues = new float[INITIAL_SLOT_CAPACITY];
        float[][] dst = new float[INITIAL_SLOT_CAPACITY][];
        float[][] src = new float[INITIAL_SLOT_CAPACITY][];
        Object[] targetForms = new Object[INITIAL_SLOT_CAPACITY];
        EllipseWeights[] coefficients = new EllipseWeights[INITIAL_SLOT_CAPACITY];
        final IdentityHashMap<Object, Boolean> targetArrays = new IdentityHashMap<>(INITIAL_SLOT_CAPACITY);
        int[] geometry = new int[0];

        void ensureTargetCapacity(int count) {
            if (extra.length < count) {
                extra = new boolean[count];
                intervals = new int[count];
                right = new int[count];
                extraIndices = new int[count];
                weights = new float[count];
                dst = new float[count][];
                targetForms = new Object[count];
                coefficients = new EllipseWeights[count];
            }
        }

        void ensureSourceCapacity(int srcCount) {
            if (src.length < srcCount) {
                src = new float[srcCount][];
                knotValues = new float[srcCount];
            }
        }

        int[] acquireGeometryBuffer(int words) {
            if (words > MAX_RETAINED_GEOMETRY_WORDS) {
                return new int[words];
            }
            if (geometry.length < words) {
                geometry = new int[words];
            }
            return geometry;
        }

        void clearReferences(int filledTargets, int filledSources, int filledCoefficients) {
            if (filledTargets > 0) {
                Arrays.fill(dst, 0, filledTargets, null);
                Arrays.fill(targetForms, 0, filledTargets, null);
            }
            if (filledSources > 0) {
                Arrays.fill(src, 0, filledSources, null);
            }
            if (filledCoefficients > 0) {
                Arrays.fill(coefficients, 0, filledCoefficients, null);
            }
            targetArrays.clear();
            // Release oversized buffers so a single large mesh does not pin memory on worker threads.
            if (extra.length > MAX_RETAINED_SLOTS) {
                extra = new boolean[INITIAL_SLOT_CAPACITY];
                intervals = new int[INITIAL_SLOT_CAPACITY];
                right = new int[INITIAL_SLOT_CAPACITY];
                extraIndices = new int[INITIAL_SLOT_CAPACITY];
                weights = new float[INITIAL_SLOT_CAPACITY];
                dst = new float[INITIAL_SLOT_CAPACITY][];
                targetForms = new Object[INITIAL_SLOT_CAPACITY];
                coefficients = new EllipseWeights[INITIAL_SLOT_CAPACITY];
            }
            if (src.length > MAX_RETAINED_SLOTS) {
                src = new float[INITIAL_SLOT_CAPACITY][];
                knotValues = new float[INITIAL_SLOT_CAPACITY];
            }
        }
    }

    /** Record holder so HotSpot C2 treats MethodHandle fields as trusted final and avoids checkCustomized. */
    private record AccessHandles(
            MethodHandle inserted,
            MethodHandle value,
            MethodHandle interval,
            MethodHandle fraction,
            MethodHandle vector,
            MethodHandle vectorSet,
            MethodHandle vectorX,
            MethodHandle vectorY,
            MethodHandle calculator,
            MethodHandle initialize,
            MethodHandle evaluate,
            MethodHandle radians,
            MethodHandle morphSources,
            MethodHandle morphExtended,
            VectorSetter directVectorSet,
            FloatGetter directVectorX,
            FloatGetter directVectorY) {}

    /** Record holder for per-form MethodHandles so C2 constant-folds fallback dispatch. */
    private record FormHandles(
            MethodHandle positions,
            MethodHandle owner,
            MethodHandle zero,
            MethodHandle blend,
            MethodHandle postBlend,
            MethodHandle updated,
            MethodHandle originX,
            MethodHandle originY,
            MethodHandle setOriginX,
            MethodHandle setOriginY) {}

    private static final class Access {
        final long cacheDomain = CACHE_DOMAINS.incrementAndGet();
        final Class<?> keyClass;
        final FormAccess meshForm, warpForm, rotationForm;
        final Object ellipse, sns;
        final AccessHandles handles;

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
            MethodHandle morphSources = MethodHandles.publicLookup()
                    .unreflect(
                            meshForm.type.getMethod("getSource").getReturnType().getMethod("getKeyformMorphTargets"))
                    .asType(MethodType.methodType(List.class, Object.class));
            MethodHandle morphExtended = MethodHandles.publicLookup()
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
            MethodHandle radians = MethodHandles.publicLookup().findStaticGetter(constants, "c", double.class);
            ellipse = typeClass.getField("ELLIPSE").get(null);
            sns = typeClass.getField("SNS_CURVE").get(null);
            MethodHandle inserted = method(keyClass, "b", boolean.class, new Class<?>[0], Object.class);
            MethodHandle value = method(keyClass, "a", float.class, new Class<?>[0], Object.class);
            MethodHandle interval = method(keyClass, "c", int.class, new Class<?>[0], Object.class);
            MethodHandle fraction = method(keyClass, "d", float.class, new Class<?>[0], Object.class);
            MethodHandle vector = MethodHandles.publicLookup()
                    .findConstructor(vectorClass, MethodType.methodType(void.class))
                    .asType(MethodType.methodType(Object.class));
            MethodHandle vectorSet = method(
                    vectorClass,
                    "set",
                    void.class,
                    new Class<?>[] {float.class, float.class},
                    Object.class,
                    float.class,
                    float.class);
            MethodHandle vectorX = method(vectorClass, "getX", float.class, new Class<?>[0], Object.class);
            MethodHandle vectorY = method(vectorClass, "getY", float.class, new Class<?>[0], Object.class);
            MethodHandle calculator = method(typeClass, "b", Object.class, new Class<?>[0], Object.class);
            MethodHandle initialize = method(
                    calculatorClass,
                    "a",
                    void.class,
                    new Class<?>[] {List.class, boolean.class, List.class, List.class},
                    Object.class,
                    List.class,
                    boolean.class,
                    List.class,
                    List.class);
            MethodHandle evaluate = method(
                    calculatorClass,
                    "a",
                    Object.class,
                    new Class<?>[] {int.class, float.class, vectorClass},
                    Object.class,
                    int.class,
                    float.class,
                    Object.class);
            handles = new AccessHandles(
                    inserted,
                    value,
                    interval,
                    fraction,
                    vector,
                    vectorSet,
                    vectorX,
                    vectorY,
                    calculator,
                    initialize,
                    evaluate,
                    radians,
                    morphSources,
                    morphExtended,
                    bindVectorSetter(vectorClass),
                    bindFloatGetter(vectorClass, "getX"),
                    bindFloatGetter(vectorClass, "getY"));
        }

        /** Each admitted form keeps its native blend and per-point update semantics.
         * WarpPointRef and MeshPointRef both apply old*(1-weight)+new*weight,
         * then setUpdated once per point; no grid/source mutation is added. */
        private static final class FormAccess {
            final Class<?> type;
            final FormHandles handles;
            final boolean rotation;
            final LocalFormState local;
            final Consumer<Object> directUpdated;

            FormAccess(Class<?> type, Class<?> blendable) throws ReflectiveOperationException {
                this.type = type;
                local = LocalFormState.bind(type);
                rotation = type.getName().endsWith(".CRotationDeformerForm");
                MethodHandle positions =
                        rotation ? null : method(type, "getPositions", float[].class, new Class<?>[0], Object.class);
                MethodHandle originX =
                        rotation ? method(type, "getOriginX", float.class, new Class<?>[0], Object.class) : null;
                MethodHandle originY =
                        rotation ? method(type, "getOriginY", float.class, new Class<?>[0], Object.class) : null;
                MethodHandle setOriginX = rotation
                        ? method(
                                type, "setOriginX", void.class, new Class<?>[] {float.class}, Object.class, float.class)
                        : null;
                MethodHandle setOriginY = rotation
                        ? method(
                                type, "setOriginY", void.class, new Class<?>[] {float.class}, Object.class, float.class)
                        : null;
                MethodHandle owner = method(type, "getSource", Object.class, new Class<?>[0], Object.class);
                MethodHandle zero = method(type, "zero", void.class, new Class<?>[0], Object.class);
                MethodHandle blend = method(
                        type,
                        "blend",
                        void.class,
                        new Class<?>[] {blendable, float.class},
                        Object.class,
                        Object.class,
                        float.class);
                MethodHandle postBlend = method(type, "postBlend", void.class, new Class<?>[0], Object.class);
                MethodHandle updated = method(type, "setUpdated", void.class, new Class<?>[0], Object.class);
                handles = new FormHandles(
                        positions, owner, zero, blend, postBlend, updated, originX, originY, setOriginX, setOriginY);
                directUpdated = directUpdate(type);
            }

            float[] readPositions(Object form) throws Throwable {
                return rotation
                        ? new float[] {
                            (float) handles.originX().invokeExact(form),
                            (float) handles.originY().invokeExact(form)
                        }
                        : (float[]) handles.positions().invokeExact(form);
            }

            void writeRotation(Object form, float x, float y) throws Throwable {
                // Native RotationDeformerPointRef.moveToOnLocal: setters have no
                // hidden side effects; setUpdated is invoked separately once.
                handles.setOriginX().invokeExact(form, (float) handles.originX().invokeExact(form) * 0f + x * 1f);
                handles.setOriginY().invokeExact(form, (float) handles.originY().invokeExact(form) * 0f + y * 1f);
            }
        }

        @SuppressWarnings("unchecked")
        static Consumer<Object> directUpdate(Class<?> form) {
            try {
                // The lambda retains the exact official per-point setter; only dispatch changes.
                var lookup = MethodHandles.lookup();
                var implementation = lookup.unreflect(form.getMethod("setUpdated"));
                var site = LambdaMetafactory.metafactory(
                        lookup,
                        "accept",
                        MethodType.methodType(Consumer.class),
                        MethodType.methodType(void.class, Object.class),
                        implementation,
                        MethodType.methodType(void.class, form));
                Consumer<Object> bound = (Consumer<Object>) site.getTarget().invokeExact();
                DIRECT_UPDATE_BINDINGS.incrementAndGet();
                return bound;
            } catch (Throwable unsupported) {
                FatalErrors.rethrowIfFatal(unsupported);
                return null;
            }
        }

        private static VectorSetter bindVectorSetter(Class<?> vectorClass) {
            try {
                var lookup = MethodHandles.lookup();
                var implementation = lookup.unreflect(vectorClass.getMethod("set", float.class, float.class));
                var site = LambdaMetafactory.metafactory(
                        lookup,
                        "set",
                        MethodType.methodType(VectorSetter.class),
                        MethodType.methodType(void.class, Object.class, float.class, float.class),
                        implementation,
                        MethodType.methodType(void.class, vectorClass, float.class, float.class));
                return (VectorSetter) site.getTarget().invokeExact();
            } catch (Throwable unsupported) {
                FatalErrors.rethrowIfFatal(unsupported);
                return null;
            }
        }

        private static FloatGetter bindFloatGetter(Class<?> owner, String methodName) {
            try {
                var lookup = MethodHandles.lookup();
                var implementation = lookup.unreflect(owner.getMethod(methodName));
                var site = LambdaMetafactory.metafactory(
                        lookup,
                        "get",
                        MethodType.methodType(FloatGetter.class),
                        MethodType.methodType(float.class, Object.class),
                        implementation,
                        MethodType.methodType(float.class, owner));
                return (FloatGetter) site.getTarget().invokeExact();
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
                    || source.size() < MIN_SOURCE_KEYFORMS
                    || keys.size() != target.size()
                    || !Float.isFinite(scale)) return false;
            int count = target.size(), srcCount = source.size(), size = -1, anchors = 0, extraCount = 0;
            UpdateScratch scratch = UPDATE_SCRATCH.get();
            scratch.ensureTargetCapacity(count);
            scratch.ensureSourceCapacity(srcCount);
            boolean[] extra = scratch.extra;
            int[] intervals = scratch.intervals, right = scratch.right, extraIndices = scratch.extraIndices;
            float[] weights = scratch.weights, knotValues = scratch.knotValues;
            AccessHandles h = this.handles;
            for (int i = 0; i < count; i++) {
                Object key = keys.get(i);
                if (key == null || key.getClass() != keyClass) return false;
                boolean isExtra = (boolean) h.inserted().invokeExact(key);
                extra[i] = isExtra;
                float knot = (float) h.value().invokeExact(key) * scale;
                if (!Float.isFinite(knot)) return false;
                if (!isExtra) {
                    if (anchors >= srcCount) return false;
                    knotValues[anchors++] = knot;
                } else {
                    intervals[i] = (int) h.interval().invokeExact(key);
                    weights[i] = (float) h.fraction().invokeExact(key);
                    if (intervals[i] < 0 || intervals[i] >= srcCount - 1 || !Float.isFinite(weights[i])) return false;
                    extraIndices[extraCount++] = i;
                }
            }
            if (extra[0] || extra[count - 1] || anchors != srcCount) return false;
            Object first = target.get(0);
            if (first == null) return false;
            FormAccess formAccess = first.getClass() == meshForm.type
                    ? meshForm
                    : first.getClass() == warpForm.type
                            ? warpForm
                            : first.getClass() == rotationForm.type ? rotationForm : null;
            if (formAccess == null) return false;
            Class<?> formClass = formAccess.type;
            FormHandles fh = formAccess.handles;
            MethodHandle owner = fh.owner(),
                    zero = fh.zero(),
                    blend = fh.blend(),
                    postBlend = fh.postBlend(),
                    updated = fh.updated();
            Consumer<Object> directUpdated = formAccess.directUpdated;
            Object formOwner = owner.invokeExact(first);
            if (formOwner == null) return false;
            float[][] dst = scratch.dst, src = scratch.src;
            Object[] targetForms = scratch.targetForms;
            EllipseWeights[] coefficients = scratch.coefficients;
            IdentityHashMap<Object, Boolean> targetArrays = scratch.targetArrays;
            int filledTargets = 0, filledSources = 0, filledCoefficients = 0;
            try {
                for (int i = 0; i < count; i++) {
                    Object form = target.get(i);
                    if (form == null || form.getClass() != formClass || owner.invokeExact(form) != formOwner)
                        return false;
                    targetForms[i] = form;
                    float[] positions = formAccess.readPositions(form);
                    dst[i] = positions;
                    filledTargets = i + 1;
                    if (positions == null) return false;
                    if (size < 0) size = positions.length;
                    if (positions.length != size || size == 0 || size % COORDINATE_STRIDE != 0) return false;
                    // Non-finite prior targets can carry JVM-dependent NaN payloads through
                    // native zero/blend/setter arithmetic. Decline before any write.
                    for (float coordinate : positions) if (!Float.isFinite(coordinate)) return false;
                    Boolean prior = targetArrays.putIfAbsent(formAccess.rotation ? form : positions, extra[i]);
                    if (prior != null && (extra[i] || prior)) return false;
                }
                for (int i = 0; i < srcCount; i++) {
                    Object form = source.get(i);
                    if (form == null || form.getClass() != formClass || owner.invokeExact(form) != formOwner)
                        return false;
                    float[] positions = formAccess.readPositions(form);
                    src[i] = positions;
                    filledSources = i + 1;
                    if (positions == null || positions.length != size) return false;
                    for (float coordinate : positions) if (!Float.isFinite(coordinate)) return false;
                    if (Boolean.TRUE.equals(targetArrays.get(formAccess.rotation ? form : positions))) return false;
                }
                int next = count - 1;
                for (int i = count - 1; i >= 0; i--) {
                    if (!extra[i]) next = i;
                    right[i] = next;
                }
                boolean coefficientMode = type == ellipse && coefficientsEnabled();
                double angleScale = type == ellipse ? (double) h.radians().invokeExact() : 0d;
                boolean localEnabled = Boolean.parseBoolean(System.getProperty(PROP_LOCAL_REFRESH, DEFAULT_ENABLED))
                        && formAccess.local != null
                        && ((List<?>) h.morphSources().invokeExact(formOwner)).isEmpty()
                        && ((List<?>) h.morphExtended().invokeExact(extension)).isEmpty();
                boolean curveCacheEnabled = Boolean.parseBoolean(System.getProperty(PROP_CURVE_CACHE, DEFAULT_ENABLED));
                int geometryLength = geometryWordCount(size, srcCount, count, anchors, localEnabled, curveCacheEnabled);
                int[] geometry = geometryLength > 0
                        ? fillGeometryInput(
                                scratch.acquireGeometryBuffer(geometryLength),
                                type == sns,
                                coefficientMode,
                                angleScale,
                                size,
                                src,
                                srcCount,
                                knotValues,
                                anchors,
                                extra,
                                intervals,
                                weights,
                                count)
                        : null;
                InterpolationCurveCache.Key lookupKey = geometry != null
                        ? InterpolationCurveCache.Key.view(cacheDomain, geometry, geometryLength)
                        : null;
                long curveBytes = geometry == null
                        ? Long.MAX_VALUE
                        : ENTRY_OVERHEAD_BYTES
                                + INT_BYTES * geometryLength
                                + (long) count * size * RESULT_BYTES_NUMERATOR / RESULT_BYTES_DENOMINATOR;
                InterpolationCurveCache.Key curveKey = lookupKey != null
                                && size >= MIN_CURVE_CACHE_COORDINATES
                                && curveBytes <= InterpolationCurveCache.MAX_ENTRY_BYTES
                                && curveCacheEnabled
                        ? lookupKey
                        : null;
                InterpolationCurveCache.Result cached = curveKey == null ? null : CURVES.get(curveKey);
                // Source-only frequency gate is detached and separately bounded, so a large
                // multi-axis sweep cannot evict its own admission history from the curve LRU.
                boolean localAdmitted = localEnabled
                        && lookupKey != null
                        && !(formAccess.rotation && type == sns)
                        && LOCAL.admit(lookupKey);
                LocalInterpolationState local = localAdmitted
                        ? LocalInterpolationState.create(
                                LOCAL, cacheDomain, geometry, geometryLength, formAccess.local, target, source, dst)
                        : null;
                boolean directUpdates = directUpdated != null
                        && Boolean.parseBoolean(System.getProperty(PROP_DIRECT_UPDATES, DEFAULT_ENABLED));
                try {
                    boolean[] notifications = local == null ? null : LOCAL.get(local.key);
                    if (notifications != null) {
                        // Native order and actual successful-write filtering, not a uniform version increment.
                        int points = size / COORDINATE_STRIDE;
                        for (int point = 0; point < points; point++) {
                            int rowBase = point * count;
                            for (int i = 0; i < count; i++) {
                                if (notifications[rowBase + i]) {
                                    notifyUpdated(targetForms[i], directUpdates, directUpdated, updated);
                                }
                            }
                        }
                        return true;
                    }
                    if (local != null) local.capture(LOCAL);
                    InterpolationCurveCache.Result capture =
                            curveKey != null && cached == null && CURVES.admit(curveKey)
                                    ? new InterpolationCurveCache.Result(count * size)
                                    : null;
                    List<Object> vectors = null;
                    List<Float> knotsList = null;
                    Object output = null;
                    boolean useCoefficients = false;
                    if (cached == null) {
                        if (coefficientMode) {
                            float[] angles = Arrays.copyOf(knotValues, anchors);
                            for (int e = 0; e < extraCount; e++) {
                                int i = extraIndices[e];
                                coefficients[i] = EllipseWeights.prepare(angles, intervals[i], weights[i], angleScale);
                            }
                            filledCoefficients = count;
                            useCoefficients = true;
                        } else {
                            vectors = new ArrayList<>(srcCount);
                            for (int i = 0; i < srcCount; i++)
                                vectors.add(h.vector().invokeExact());
                            output = h.vector().invokeExact();
                            knotsList = new ArrayList<>(anchors);
                            for (int i = 0; i < anchors; i++) knotsList.add(knotValues[i]);
                        }
                    }
                    // Every refusal and handle binding finishes above this mutation boundary.
                    Object left = first;
                    for (int i = 1; i < count; i++) {
                        Object form = targetForms[i];
                        if (!extra[i]) {
                            left = form;
                            continue;
                        }
                        zero.invokeExact(form);
                        blend.invokeExact(form, left, 1f - weights[i]);
                        blend.invokeExact(form, targetForms[right[i]], weights[i]);
                        postBlend.invokeExact(form);
                    }
                    if (cached != null) {
                        // Replay every actual point write in native order. Blending above and
                        // old*0+new*1 below are retained, including signed-zero/NaN behavior.
                        for (int offset = 0; offset < size; offset += COORDINATE_STRIDE) {
                            int pointRow = (offset / COORDINATE_STRIDE) * count;
                            for (int e = 0; e < extraCount; e++) {
                                int i = extraIndices[e];
                                int slot = i * size + offset;
                                if (cached.present(slot)) {
                                    float x = cached.get(slot), y = cached.get(slot + 1);
                                    if (formAccess.rotation) formAccess.writeRotation(targetForms[i], x, y);
                                    else {
                                        float[] row = dst[i];
                                        row[offset] = row[offset] * 0f + x * 1f;
                                        row[offset + 1] = row[offset + 1] * 0f + y * 1f;
                                    }
                                    if (local != null) local.actualWrites[pointRow + i] = true;
                                    notifyUpdated(targetForms[i], directUpdates, directUpdated, updated);
                                }
                            }
                        }
                        if (local != null) local.completed(LOCAL, target, dst);
                        return true;
                    }
                    if (useCoefficients) {
                        for (int offset = 0; offset < size; offset += COORDINATE_STRIDE) {
                            int pointRow = (offset / COORDINATE_STRIDE) * count;
                            for (int e = 0; e < extraCount; e++) {
                                int i = extraIndices[e];
                                EllipseWeights cw = coefficients[i];
                                float x = cw.evaluate(src, offset), y = cw.evaluate(src, offset + 1);
                                if (capture != null) capture.set(i * size + offset, x, y);
                                if (formAccess.rotation) formAccess.writeRotation(targetForms[i], x, y);
                                else {
                                    float[] row = dst[i];
                                    row[offset] = row[offset] * 0f + x * 1f;
                                    row[offset + 1] = row[offset + 1] * 0f + y * 1f;
                                }
                                if (local != null) local.actualWrites[pointRow + i] = true;
                                notifyUpdated(targetForms[i], directUpdates, directUpdated, updated);
                            }
                        }
                    } else {
                        // A fresh SNS calculator is owned by this call and only initialized as
                        // open/no-corner. Native initialize replaces its point/spline banks;
                        // sharing with closed curves would retain native corner-index state.
                        Object snsCurve = type == sns ? h.calculator().invokeExact(type) : null;
                        VectorSetter directVectorSet = h.directVectorSet();
                        FloatGetter directVectorX = h.directVectorX();
                        FloatGetter directVectorY = h.directVectorY();
                        for (int offset = 0; offset < size; offset += COORDINATE_STRIDE) {
                            int pointRow = (offset / COORDINATE_STRIDE) * count;
                            for (int i = 0; i < srcCount; i++) {
                                float sx = src[i][offset], sy = src[i][offset + 1];
                                if (directVectorSet != null) directVectorSet.set(vectors.get(i), sx, sy);
                                else h.vectorSet().invokeExact(vectors.get(i), sx, sy);
                            }
                            Object curve =
                                    type == sns ? snsCurve : h.calculator().invokeExact(type);
                            h.initialize()
                                    .invokeExact(curve, (List<?>) vectors, true, (List<?>) null, (List<?>) knotsList);
                            for (int e = 0; e < extraCount; e++) {
                                int i = extraIndices[e];
                                Object result = h.evaluate().invokeExact(curve, intervals[i], weights[i], output);
                                if (result != null) {
                                    float x = directVectorX != null
                                            ? directVectorX.get(result)
                                            : (float) h.vectorX().invokeExact(result);
                                    float y = directVectorY != null
                                            ? directVectorY.get(result)
                                            : (float) h.vectorY().invokeExact(result);
                                    if (capture != null) capture.set(i * size + offset, x, y);
                                    if (formAccess.rotation) formAccess.writeRotation(targetForms[i], x, y);
                                    else {
                                        float[] row = dst[i];
                                        row[offset] = row[offset] * 0f + x * 1f;
                                        row[offset + 1] = row[offset + 1] * 0f + y * 1f;
                                    }
                                    if (local != null) local.actualWrites[pointRow + i] = true;
                                    notifyUpdated(targetForms[i], directUpdates, directUpdated, updated);
                                }
                            }
                        }
                    }
                    if (capture != null) CURVES.put(curveKey, capture);
                    if (local != null) local.completed(LOCAL, target, dst);
                    return true;
                } finally {
                    if (local != null) local.release(LOCAL);
                }
            } finally {
                scratch.clearReferences(filledTargets, filledSources, filledCoefficients);
            }
        }

        private static void notifyUpdated(
                Object form, boolean directUpdates, Consumer<Object> directUpdated, MethodHandle updated)
                throws Throwable {
            if (directUpdates) {
                directUpdated.accept(form);
            } else {
                updated.invokeExact(form);
            }
        }

        private static int geometryWordCount(
                int size,
                int srcCount,
                int targetCount,
                int anchorCount,
                boolean localEnabled,
                boolean curveCacheEnabled) {
            if (!localEnabled && (size < MIN_CURVE_CACHE_COORDINATES || !curveCacheEnabled)) return NO_GEOMETRY;
            long coordinates = (long) targetCount * size;
            long words =
                    GEOMETRY_HEADER_WORDS + (long) srcCount * size + anchorCount + EXTRA_DESCRIPTOR_WORDS * targetCount;
            long retained = ENTRY_OVERHEAD_BYTES
                    + INT_BYTES * words
                    + INT_BYTES * coordinates
                    + coordinates / COORDINATE_STRIDE;
            if (retained > InterpolationCurveCache.MAX_ENTRY_BYTES && !localEnabled) return NO_GEOMETRY;
            if (INT_BYTES * words + ENTRY_OVERHEAD_BYTES > LocalInterpolationCache.MAX_ENTRY_BYTES) return NO_GEOMETRY;
            return (int) words;
        }

        private static int[] fillGeometryInput(
                int[] signature,
                boolean snsMode,
                boolean coefficientMode,
                double angleScale,
                int size,
                float[][] src,
                int srcCount,
                float[] knotValues,
                int anchorCount,
                boolean[] extra,
                int[] intervals,
                float[] weights,
                int targetCount) {
            int at = 0;
            signature[at++] = snsMode ? 1 : 0;
            signature[at++] = coefficientMode ? 1 : 0;
            long angleBits = Double.doubleToRawLongBits(angleScale);
            signature[at++] = (int) (angleBits >>> DOUBLE_HIGH_WORD_SHIFT);
            signature[at++] = (int) angleBits;
            signature[at++] = size;
            signature[at++] = srcCount;
            signature[at++] = targetCount;
            signature[at++] = anchorCount;
            for (int i = 0; i < srcCount; i++) {
                float[] coordinatesOfSource = src[i];
                for (float coordinate : coordinatesOfSource) {
                    signature[at++] = Float.floatToRawIntBits(coordinate);
                }
            }
            for (int i = 0; i < anchorCount; i++) {
                signature[at++] = Float.floatToRawIntBits(knotValues[i]);
            }
            for (int i = 0; i < targetCount; i++) {
                signature[at++] = extra[i] ? 1 : 0;
                signature[at++] = intervals[i];
                signature[at++] = Float.floatToRawIntBits(weights[i]);
            }
            return signature;
        }
    }
}
