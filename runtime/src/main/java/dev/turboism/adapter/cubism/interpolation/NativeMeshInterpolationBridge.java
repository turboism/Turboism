package dev.turboism.adapter.cubism.interpolation;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Internal entrypoint for the pinned multi-version interpolation hook. No host dependency. */
public final class NativeMeshInterpolationBridge {
    /**
     * Exact reviewed host artifacts admitted by the interpolation hook.
     */
    public static final java.util.Set<String> HOST_SHA256S = ReviewedHostArtifacts.reviewedSha256s();

    private static final java.util.concurrent.ConcurrentHashMap<
                    Path, dev.turboism.mapping.verification.HostArtifactDigest>
            VERIFIED_FINGERPRINTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Records a previously verified host artifact fingerprint in the bridge cache so subsequent
     * operations avoid re-reading the entire host JAR.
     *
     * @param artifact path to the host artifact
     * @param digest verified artifact digest
     */
    public static void cacheVerifiedArtifact(
            final Path artifact, final dev.turboism.mapping.verification.HostArtifactDigest digest) {
        java.util.Objects.requireNonNull(artifact, "artifact");
        java.util.Objects.requireNonNull(digest, "digest");
        try {
            VERIFIED_FINGERPRINTS.put(artifact.toRealPath(), digest);
        } catch (java.io.IOException ignored) {
            VERIFIED_FINGERPRINTS.put(artifact.toAbsolutePath().normalize(), digest);
        }
    }

    /**
     * Retrieves the cached verified host artifact digest if available.
     *
     * @param artifact path to the host artifact
     * @return the cached digest, or null if not present
     */
    public static dev.turboism.mapping.verification.HostArtifactDigest getCachedVerifiedFingerprint(
            final Path artifact) {
        java.util.Objects.requireNonNull(artifact, "artifact");
        try {
            return VERIFIED_FINGERPRINTS.get(artifact.toRealPath());
        } catch (java.io.IOException ignored) {
            return VERIFIED_FINGERPRINTS.get(artifact.toAbsolutePath().normalize());
        }
    }

    /**
     * Checks whether the given host artifact path matches an admitted reviewed Cubism artifact,
     * using the cached verified fingerprint if available to avoid re-reading the file.
     *
     * @param artifact path to the host artifact
     * @return true if the artifact matches a reviewed Cubism build
     */
    public static boolean isAdmittedHostArtifact(final Path artifact) {
        try {
            requireAdmittedHostArtifact(artifact);
            return true;
        } catch (java.io.IOException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Asserts that the given host artifact path matches an admitted reviewed Cubism artifact,
     * using the cached verified fingerprint if available to avoid re-reading the whole JAR.
     *
     * @param artifact path to the host artifact
     * @throws IllegalArgumentException if the artifact is unreviewed
     * @throws java.io.IOException if the artifact cannot be read
     */
    public static void requireAdmittedHostArtifact(final Path artifact) throws java.io.IOException {
        java.util.Objects.requireNonNull(artifact, "artifact");
        final Path realPath = artifact.toRealPath();
        final dev.turboism.mapping.verification.HostArtifactDigest cached = VERIFIED_FINGERPRINTS.get(realPath);
        if (cached != null) {
            if (!HOST_SHA256S.contains(cached.sha256())) {
                throw new IllegalArgumentException("unreviewed host artifact");
            }
            return;
        }
        final dev.turboism.mapping.verification.HostArtifactDigest measured =
                dev.turboism.mapping.verification.HostArtifactDigest.from(realPath);
        if (!HOST_SHA256S.contains(measured.sha256())) {
            throw new IllegalArgumentException("unreviewed host artifact");
        }
        VERIFIED_FINGERPRINTS.put(realPath, measured);
    }

    static final String EXTENSION =
            "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension";
    private static volatile boolean enabled;
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
                    | java.security.NoSuchAlgorithmException
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
    }
    /**
     * @return calls that completed the verified coefficient path
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
            boolean applied = access.get().update(target, source, keys, type, scale);
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

    private static final class Access {
        final Class<?> formClass, keyClass;
        final Object ellipse;
        final MethodHandle positions, owner, zero, blend, postBlend, updated;
        final MethodHandle inserted, value, interval, fraction;
        final MethodHandle vector, vectorSet, vectorX, vectorY, calculator, initialize, evaluate;
        final MethodHandle radians;
        final java.util.function.Consumer<Object> directUpdated;

        Access(Class<?> extension)
                throws ReflectiveOperationException, java.io.IOException, java.net.URISyntaxException,
                        java.security.NoSuchAlgorithmException {
            if (!extension.getName().equals(EXTENSION)) throw new IllegalArgumentException("extension type");
            Path artifact = Path.of(extension
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toRealPath();
            requireAdmittedHostArtifact(artifact);
            ClassLoader loader = extension.getClassLoader();
            formClass = host("com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm", loader, artifact);
            keyClass = host(EXTENSION + "$b", loader, artifact);
            Class<?> typeClass = host(
                    "com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType",
                    loader,
                    artifact);
            Class<?> vectorClass = host("com.live2d.graphics3d.type.GVector2", loader, artifact);
            Class<?> calculatorClass =
                    host("com.live2d.cubism.doc.model.interpolator.extendedInterpolation.a", loader, artifact);
            Class<?> blendable = host("com.live2d.core.IBlendable", loader, artifact);
            Class<?> constants = host("jp.noids.util.ap", loader, artifact);
            radians = MethodHandles.publicLookup().findStaticGetter(constants, "c", double.class);
            ellipse = typeClass.getField("ELLIPSE").get(null);
            positions = method(formClass, "getPositions", float[].class, new Class<?>[0], Object.class);
            owner = method(formClass, "getSource", Object.class, new Class<?>[0], Object.class);
            zero = method(formClass, "zero", void.class, new Class<?>[0], Object.class);
            blend = method(
                    formClass,
                    "blend",
                    void.class,
                    new Class<?>[] {blendable, float.class},
                    Object.class,
                    Object.class,
                    float.class);
            postBlend = method(formClass, "postBlend", void.class, new Class<?>[0], Object.class);
            updated = method(formClass, "setUpdated", void.class, new Class<?>[0], Object.class);
            directUpdated = directUpdate(formClass);
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
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable unsupported) {
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

        boolean update(List<?> target, List<?> source, List<?> keys, Object type, float scale) throws Throwable {
            if (type != ellipse
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
            if (first == null || first.getClass() != formClass) return false;
            Object mesh = owner.invokeExact(first);
            if (mesh == null) return false;
            float[][] dst = new float[count][], src = new float[source.size()][];
            for (int i = 0; i < count; i++) {
                Object form = target.get(i);
                if (form == null || form.getClass() != formClass || owner.invokeExact(form) != mesh) return false;
                dst[i] = (float[]) positions.invokeExact(form);
                if (dst[i] == null) return false;
                if (size < 0) size = dst[i].length;
                if (dst[i].length != size || size == 0 || size % 2 != 0) return false;
                for (int j = 0; j < i; j++) if ((extra[i] || extra[j]) && dst[i] == dst[j]) return false;
            }
            for (int i = 0; i < src.length; i++) {
                Object form = source.get(i);
                if (form == null || form.getClass() != formClass || owner.invokeExact(form) != mesh) return false;
                src[i] = (float[]) positions.invokeExact(form);
                if (src[i] == null || src[i].length != size) return false;
                for (float coordinate : src[i]) if (!Float.isFinite(coordinate)) return false;
                for (int j = 0; j < count; j++) if (extra[j] && src[i] == dst[j]) return false;
            }
            int next = count - 1;
            for (int i = count - 1; i >= 0; i--) {
                if (!extra[i]) next = i;
                right[i] = next;
            }
            List<Object> vectors = new ArrayList<>(src.length);
            for (int i = 0; i < src.length; i++) vectors.add(vector.invokeExact());
            Object output = vector.invokeExact();
            EllipseWeights[] coefficients = null;
            if (coefficientsEnabled()) {
                float[] angles = new float[knots.size()];
                for (int i = 0; i < angles.length; i++) angles[i] = knots.get(i);
                double angleScale = (double) radians.invokeExact();
                coefficients = new EllipseWeights[count];
                for (int i = 0; i < count; i++)
                    if (extra[i])
                        coefficients[i] = EllipseWeights.prepare(angles, intervals[i], weights[i], angleScale);
            }
            boolean directUpdates = coefficients != null
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
            for (int offset = 0; offset < size; offset += 2) {
                if (coefficients != null) {
                    for (int i = 0; i < count; i++)
                        if (extra[i]) {
                            dst[i][offset] = dst[i][offset] * 0f + coefficients[i].evaluate(src, offset) * 1f;
                            dst[i][offset + 1] =
                                    dst[i][offset + 1] * 0f + coefficients[i].evaluate(src, offset + 1) * 1f;
                            if (directUpdates) directUpdated.accept(target.get(i));
                            else updated.invokeExact(target.get(i));
                        }
                    continue;
                }
                for (int i = 0; i < src.length; i++)
                    vectorSet.invokeExact(vectors.get(i), src[i][offset], src[i][offset + 1]);
                Object curve = calculator.invokeExact(type);
                initialize.invokeExact(curve, (List<?>) vectors, true, (List<?>) null, (List<?>) knots);
                for (int i = 0; i < count; i++)
                    if (extra[i]) {
                        Object result = evaluate.invokeExact(curve, intervals[i], weights[i], output);
                        if (result != null) {
                            dst[i][offset] = dst[i][offset] * 0f + (float) vectorX.invokeExact(result) * 1f;
                            dst[i][offset + 1] = dst[i][offset + 1] * 0f + (float) vectorY.invokeExact(result) * 1f;
                            updated.invokeExact(target.get(i));
                        }
                    }
            }
            return true;
        }
    }
}
