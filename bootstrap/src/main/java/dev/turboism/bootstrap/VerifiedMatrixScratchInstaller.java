package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchBridge;
import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTarget;
import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.ClassFileTransformer;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/**
 * Exact-host, explicitly requested installation of invocation-local matrix scratch storage.
 *
 * <p>Two transformers are registered: a guard that observes every class the weave reads and retires the
 * admission on any changed body, and the transform that rewrites one parent-matrix traversal. The
 * admission slot is published before either of them is registered (so a woven site never finds it
 * absent) and is only armed after the weave and every dependency re-verification have been proven.</p>
 *
 * <p>The protocol itself lives in {@link VerifiedInstall}: the guard is a registration without a class
 * of its own, the rewritten traversal is restored on close by removing every registration and
 * retransforming the owner, and a failed removal keeps the handle open for a retry instead of reporting a
 * settled installation.</p>
 */
final class VerifiedMatrixScratchInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.render.matrix-scratch";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** The one matrix traversal this hook rewrites; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /** The close proof re-captures the one rewritten class; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "matrix scratch";
    private static final String PRODUCT_CLASS = MatrixScratchTarget.PRODUCT_OWNER.replace('/', '.');
    private static final String PRODUCT_METHOD = MatrixScratchTarget.PRODUCT_METHOD;
    private static final String GET_LOCAL_TO_PARENT = "getLocalToParentMatrix";
    private static final String GET_ENTITY = "getEntity";
    private static final String GET_PARENT_ENTITY = "getParentEntity";
    private static final String GET_TRANSFORM = "getTransform";
    private static final String GET_POSITION = "getPosition";
    private static final String GET_EULER_ANGLES = "getEulerAngles";
    private static final String GET_SCALE = "getScale";
    private static final String VECTOR_GET_X = "getX";
    private static final String VECTOR_GET_Y = "getY";
    private static final String VECTOR_GET_Z = "getZ";
    private static final String MATRIX_INIT = "<init>";
    private static final String MATRIX_INIT_DESC = "()V";

    private final InstallContext context;
    private final Class<?> target;
    private final MatrixScratchTransformer transformer;
    private final MatrixScratchBridge bridge;
    private final AtomicBoolean admission = new AtomicBoolean();
    private final AtomicReference<Throwable> dependencyRejection = new AtomicReference<>();
    private final Map<Class<?>, List<Body>> dependencies = new LinkedHashMap<>();
    private final List<WeaveTarget> targets = new ArrayList<>();
    private final ClassFileTransformer dependencyGuard;
    private Properties properties;
    private boolean closed;
    private VerifiedInstall<ReviewedHostContract.Bound<String>> hook;

    private record Body(String name, String descriptor, List<String> shape) {}

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES =
            ReviewedHostContract.candidates(MatrixScratchTransformer.targetClasses(), version -> HOOK_ID);

    VerifiedMatrixScratchInstaller(final InstallContext context) throws Exception {
        this.context = context;
        final var instrumentation = context.instrumentation();
        final Path source = context.artifact().toAbsolutePath().normalize();
        final ClassLoader loader = context.hostLoader();
        final var contract =
                ReviewedHostContract.requireBound(ReviewedHostContract.resolve(source, CANDIDATES), FEATURE_NAME);
        if (context.jvmFeature() < 17 || !instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("matrix scratch requires JVM17+ retransformation");
        }
        target = Class.forName(MatrixScratchTransformer.OWNER.replace('/', '.'), false, loader);
        Class<?> matrix = Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), false, loader);
        Class<?> product = Class.forName(PRODUCT_CLASS, false, loader);
        Map<Class<?>, byte[]> captured = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(source.toFile())) {
            require(
                    jar,
                    source,
                    loader,
                    target,
                    MatrixScratchTransformer.METHOD,
                    MatrixScratchTransformer.DESCRIPTOR,
                    captured);
            require(jar, source, loader, target.getMethod(GET_LOCAL_TO_PARENT), captured);
            Method position = target.getMethod(GET_POSITION);
            require(jar, source, loader, position, captured);
            require(jar, source, loader, target.getMethod(GET_EULER_ANGLES), captured);
            require(jar, source, loader, target.getMethod(GET_SCALE), captured);
            Class<?> vector = position.getReturnType();
            require(jar, source, loader, vector.getMethod(VECTOR_GET_X), captured);
            require(jar, source, loader, vector.getMethod(VECTOR_GET_Y), captured);
            require(jar, source, loader, vector.getMethod(VECTOR_GET_Z), captured);
            Method entity = target.getMethod(GET_ENTITY);
            require(jar, source, loader, entity, captured);
            require(jar, source, loader, entity.getReturnType().getMethod(GET_PARENT_ENTITY), captured);
            require(jar, source, loader, entity.getReturnType().getMethod(GET_TRANSFORM), captured);
            // The original product uses scalar accessors; the candidate uses backing arrays.
            // Verify all matrix method bodies, not just the new multiplication descriptor.
            require(jar, source, loader, matrix, MATRIX_INIT, MATRIX_INIT_DESC, captured);
            for (Method method : matrix.getDeclaredMethods()) require(jar, source, loader, method, captured);
            require(jar, source, loader, product.getMethod(PRODUCT_METHOD, matrix, matrix), captured);
            transformer = new MatrixScratchTransformer(
                    loader, source, VerifiedHookInstallerSupport.readReferenceBytes(jar, target));
            bridge = new MatrixScratchBridge(loader, admission);
        }
        dependencyGuard = new ClassFileTransformer() {
            @Override
            public byte[] transform(
                    Module module,
                    ClassLoader definingLoader,
                    String name,
                    Class<?> redefined,
                    ProtectionDomain domain,
                    byte[] bytes) {
                List<Body> bodies = dependencies.get(redefined);
                if (bodies == null) return null;
                try {
                    VerifiedHookInstallerSupport.attestClassSource(
                            redefined,
                            definingLoader,
                            source,
                            "matrix dependency loader/source mismatch: " + redefined.getName());
                    verifyBodies(redefined, bytes, bodies);
                } catch (Exception | LinkageError rejected) {
                    dependencyRejection.compareAndSet(null, rejected);
                    admission.set(false);
                }
                return null;
            }
        };
        targets.add(WeaveTarget.registeredOnly(dependencyGuard));
        targets.add(
                WeaveTarget.single(target, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
        contract.requireUnchanged(source);
    }

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested, int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<String>> resolve(final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES) instanceof ReviewedHostContract.Bound<String> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    /**
     * @return the hook's spec: the dependencies were verified and the restoration baseline captured by
     *     the constructor, so the plan only hands the two registrations over in the order the
     *     hand-rolled protocol used
     */
    private WeaveSpec<ReviewedHostContract.Bound<String>> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                FEATURE_NAME,
                VerifiedMatrixScratchInstaller::resolve,
                new WeavePlan<>((contract, source) -> List.copyOf(targets), this::unchanged),
                (contract, launch) -> BridgeLayer.of(this::publish, this::withdraw),
                new WeaveEffects<ReviewedHostContract.Bound<String>>() {
                    @Override
                    public void confirmed(final VerifiedInstall<ReviewedHostContract.Bound<String>> installed) {
                        arm();
                    }

                    @Override
                    public void withdrawn(final VerifiedInstall<ReviewedHostContract.Bound<String>> closing) {
                        // Disarm both the admission gate and bridge before restoring original bytecode.
                        admission.set(false);
                        bridge.deactivate();
                    }
                });
    }

    /**
     * @return the failure detail of a dependency body that changed after the constructor's preparation
     */
    private String unchanged(final List<WeaveTarget> targets) {
        final Throwable rejected = dependencyRejection.get();
        return rejected == null ? null : "dependencies changed during installation: " + rejected;
    }

    private void require(JarFile jar, Path source, ClassLoader loader, Method method, Map<Class<?>, byte[]> captured)
            throws Exception {
        require(
                jar,
                source,
                loader,
                method.getDeclaringClass(),
                method.getName(),
                MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .descriptorString(),
                captured);
    }

    private void require(
            JarFile jar,
            Path source,
            ClassLoader loader,
            Class<?> type,
            String name,
            String descriptor,
            Map<Class<?>, byte[]> captured)
            throws Exception {
        VerifiedHookInstallerSupport.attestClassSource(
                type, loader, source, "matrix dependency loader/source mismatch: " + type.getName());
        byte[] actual = captured.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    context.instrumentation(),
                    type,
                    "matrix dependency is not modifiable: " + type.getName(),
                    "matrix class capture missing: " + type.getName(),
                    "matrix inspection observer removal failed");
            captured.put(type, actual);
        }
        List<String> expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(
                        jar, type, "matrix reference class missing: " + type.getName()),
                type.getName().replace('.', '/'),
                name,
                descriptor);
        Body body = new Body(name, descriptor, expected);
        verifyBodies(type, actual, List.of(body));
        dependencies.computeIfAbsent(type, ignored -> new ArrayList<>()).add(body);
    }

    private static void verifyBodies(Class<?> type, byte[] actual, List<Body> bodies) {
        String owner = type.getName().replace('.', '/');
        for (Body body : bodies) {
            if (body.shape() == null
                    || !body.shape().equals(ReviewedMethodShape.read(actual, owner, body.name(), body.descriptor()))) {
                throw new IllegalStateException(
                        "matrix dependency body mismatch: " + owner + "." + body.name() + body.descriptor());
            }
        }
    }

    /**
     * Publishes the admission slot and re-verifies every live dependency, both before the first
     * transformer is registered: preparation can precede installation, so the bodies are checked again
     * against the definitions the host is running now.
     */
    private void publish() throws Exception {
        properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(MatrixScratchTransformer.ADMISSION_PROPERTY)
                    || properties.containsKey(MatrixScratchBridge.CALLBACK_PROPERTY)
                    || properties.containsKey(MatrixScratchBridge.STATS_PROPERTY)) {
                throw new IllegalStateException("matrix admission slot is occupied");
            }
            properties.put(MatrixScratchTransformer.ADMISSION_PROPERTY, admission);
            properties.put(MatrixScratchBridge.CALLBACK_PROPERTY, bridge.callback());
            properties.put(MatrixScratchBridge.STATS_PROPERTY, bridge.statistics());
        }
        for (final var dependency : dependencies.entrySet()) {
            verifyBodies(dependency.getKey(), capture(dependency.getKey()), dependency.getValue());
        }
    }

    /** Disarms reuse and withdraws the admission slot, if this installation still owns it. */
    private void withdraw() {
        admission.set(false);
        bridge.close();
        final Properties installed = properties;
        if (installed == null) return;
        synchronized (installed) {
            if (installed.get(MatrixScratchTransformer.ADMISSION_PROPERTY) == admission) {
                installed.remove(MatrixScratchTransformer.ADMISSION_PROPERTY);
            }
            if (installed.get(MatrixScratchBridge.CALLBACK_PROPERTY) == bridge.callback()) {
                installed.remove(MatrixScratchBridge.CALLBACK_PROPERTY);
            }
            if (installed.get(MatrixScratchBridge.STATS_PROPERTY) == bridge.statistics()) {
                installed.remove(MatrixScratchBridge.STATS_PROPERTY);
            }
        }
    }

    /** Arms reuse, but only if the slot is still ours and no guard invocation rejected a dependency. */
    private void arm() {
        final Properties installed = properties;
        synchronized (installed) {
            // Identity comparison checks that this installer still owns every published slot.
            @SuppressWarnings("ReferenceEquality")
            boolean ownershipChanged = System.getProperties() != installed
                    || installed.get(MatrixScratchTransformer.ADMISSION_PROPERTY) != admission
                    || installed.get(MatrixScratchBridge.CALLBACK_PROPERTY) != bridge.callback()
                    || installed.get(MatrixScratchBridge.STATS_PROPERTY) != bridge.statistics();
            if (ownershipChanged) {
                throw new IllegalStateException("matrix admission ownership changed");
            }
            admission.set(true);
            bridge.activate();
            // A guard invocation racing arming cannot revive a rejected dependency.
            if (dependencyRejection.get() != null) {
                admission.set(false);
                bridge.deactivate();
                throw new IllegalStateException(
                        "matrix dependencies changed during installation", dependencyRejection.get());
            }
        }
    }

    private byte[] capture(final Class<?> type) throws Exception {
        return VerifiedHookInstallerSupport.captureLoadedBytes(
                context.instrumentation(),
                type,
                "matrix dependency is not modifiable: " + type.getName(),
                "matrix class capture missing: " + type.getName(),
                "matrix inspection observer removal failed");
    }

    /**
     * Binds the hook and installs the weave through {@link VerifiedInstall}. The handle is kept before
     * the weave runs, so a failed installation can still prove the restoration of what it rewrote.
     *
     * @throws IllegalStateException when the installer is closed or the artifact is not an admitted
     *     matrix-scratch host
     * @throws Exception when the admission slot is taken, a dependency changed, or the weave is not
     *     proven
     */
    synchronized void install() throws Exception {
        if (closed) throw new IllegalStateException("matrix installer is closed");
        if (hook != null) return;
        if (!(VerifiedInstall.bind(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<ReviewedHostContract.Bound<String>> installed)) {
            throw new IllegalStateException("matrix scratch refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
        hook.weave();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
