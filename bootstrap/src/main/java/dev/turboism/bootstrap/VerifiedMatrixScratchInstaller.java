package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.geometry.MatrixScratchTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Exact-host, explicitly requested installation of invocation-local matrix scratch storage. */
final class VerifiedMatrixScratchInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.render.matrix-scratch";
    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final MatrixScratchTransformer transformer;
    private final AtomicBoolean admission = new AtomicBoolean();
    private final AtomicBoolean dependencyRejected = new AtomicBoolean();
    private final Map<Class<?>, List<Body>> dependencies = new LinkedHashMap<>();
    private final List<ClassFileTransformer> registered = new ArrayList<>();
    private final String originalTargetSha;
    private final ClassFileTransformer dependencyGuard;
    private Properties properties;
    private boolean installed, closed, restored;

    private record Body(String name, String descriptor, List<String> shape) { }

    static boolean admitted(HostArtifactDigest digest, RuntimeStartupConfig config, boolean requested, int jvm) {
        return requested && jvm >= 17 && config != null && config.hookEnabled(HOOK_ID) && supported(digest);
    }

    private static boolean supported(HostArtifactDigest digest) {
        return ReviewedHostArtifacts.CUBISM_5_2_03.equals(digest)
            || ReviewedHostArtifacts.CUBISM_5_3_02.equals(digest)
            || ReviewedHostArtifacts.CUBISM_5_3_03.equals(digest);
    }

    VerifiedMatrixScratchInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader) throws Exception {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        Path source = Objects.requireNonNull(artifact, "artifact").toAbsolutePath().normalize();
        if (!supported(HostArtifactDigest.from(source))) throw new IllegalArgumentException("matrix scratch requires an exact reviewed Editor");
        if (Runtime.version().feature() < 17 || !instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("matrix scratch requires JVM17+ retransformation");
        }
        target = Class.forName(MatrixScratchTransformer.OWNER.replace('/', '.'), false, loader);
        Class<?> matrix = Class.forName(MatrixScratchTransformer.MATRIX.replace('/', '.'), false, loader);
        Class<?> product = Class.forName("com.live2d.graphics3d.type.a", false, loader);
        Map<Class<?>, byte[]> captured = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(source.toFile())) {
            require(jar, source, loader, target, MatrixScratchTransformer.METHOD, MatrixScratchTransformer.DESCRIPTOR, captured);
            require(jar, source, loader, target.getMethod("getLocalToParentMatrix"), captured);
            Method entity = target.getMethod("getEntity");
            require(jar, source, loader, entity, captured);
            require(jar, source, loader, entity.getReturnType().getMethod("getParentEntity"), captured);
            require(jar, source, loader, entity.getReturnType().getMethod("getTransform"), captured);
            // The original product uses scalar accessors; the candidate uses backing arrays.
            // Verify all matrix method bodies, not just the new multiplication descriptor.
            require(jar, source, loader, matrix, "<init>", "()V", captured);
            for (Method method : matrix.getDeclaredMethods()) require(jar, source, loader, method, captured);
            require(jar, source, loader, product.getMethod("a", matrix, matrix), captured);
            transformer = new MatrixScratchTransformer(loader, source, reference(jar, target));
            originalTargetSha = sha256(captured.get(target));
        }
        dependencyGuard = new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader definingLoader, String name,
                    Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                List<Body> bodies = dependencies.get(redefined);
                if (bodies == null) return null;
                try {
                    attest(redefined, definingLoader, source);
                    verifyBodies(redefined, bytes, bodies);
                } catch (Exception | LinkageError rejected) {
                    dependencyRejected.set(true);
                    admission.set(false);
                }
                return null;
            }
        };
    }

    private void require(JarFile jar, Path source, ClassLoader loader, Method method,
            Map<Class<?>, byte[]> captured) throws Exception {
        require(jar, source, loader, method.getDeclaringClass(), method.getName(),
            MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString(), captured);
    }

    private void require(JarFile jar, Path source, ClassLoader loader, Class<?> type, String name,
            String descriptor, Map<Class<?>, byte[]> captured) throws Exception {
        attest(type, loader, source);
        byte[] actual = captured.get(type);
        if (actual == null) { actual = capture(type); captured.put(type, actual); }
        List<String> expected = ReviewedMethodShape.read(reference(jar, type), type.getName().replace('.', '/'), name, descriptor);
        Body body = new Body(name, descriptor, expected);
        verifyBodies(type, actual, List.of(body));
        dependencies.computeIfAbsent(type, ignored -> new ArrayList<>()).add(body);
    }

    private static void verifyBodies(Class<?> type, byte[] actual, List<Body> bodies) {
        String owner = type.getName().replace('.', '/');
        for (Body body : bodies) {
            if (body.shape() == null || !body.shape().equals(ReviewedMethodShape.read(actual, owner, body.name(), body.descriptor()))) {
                throw new IllegalStateException("matrix dependency body mismatch: " + owner + "." + body.name() + body.descriptor());
            }
        }
    }

    private static void attest(Class<?> type, ClassLoader loader, Path source) throws Exception {
        if (type.getClassLoader() != loader || type.getProtectionDomain().getCodeSource() == null
            || !source.equals(Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("matrix dependency loader/source mismatch: " + type.getName());
        }
    }

    private static byte[] reference(JarFile jar, Class<?> type) throws Exception {
        var entry = jar.getJarEntry(type.getName().replace('.', '/') + ".class");
        if (entry == null) throw new IllegalArgumentException("matrix reference class missing: " + type.getName());
        try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
    }

    private byte[] capture(Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException("matrix dependency is not modifiable: " + type.getName());
        AtomicReference<byte[]> bytes = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name,
                    Class<?> redefined, ProtectionDomain domain, byte[] value) {
                if (redefined == type && value != null) bytes.set(value.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try { instrumentation.retransformClasses(type); }
        finally {
            if (!instrumentation.removeTransformer(observer)) throw new IllegalStateException("matrix inspection observer removal failed");
        }
        if (bytes.get() == null) throw new IllegalStateException("matrix class capture missing: " + type.getName());
        return bytes.get();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    synchronized void install() throws Exception {
        if (closed) throw new IllegalStateException("matrix installer is closed");
        if (installed) return;
        properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(MatrixScratchTransformer.ADMISSION_PROPERTY)) {
                throw new IllegalStateException("matrix admission slot is occupied");
            }
            properties.put(MatrixScratchTransformer.ADMISSION_PROPERTY, admission);
        }
        try {
            // Preparation can precede installation. Recheck all live dependencies at admission.
            for (var dependency : dependencies.entrySet()) verifyBodies(dependency.getKey(), capture(dependency.getKey()), dependency.getValue());
            instrumentation.addTransformer(dependencyGuard, true);
            registered.add(dependencyGuard);
            instrumentation.addTransformer(transformer, true);
            registered.add(transformer);
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null || dependencyRejected.get()) {
                throw new IllegalStateException("matrix transform was not admitted: " + transformer.failure());
            }
            synchronized (properties) {
                if (System.getProperties() != properties || properties.get(MatrixScratchTransformer.ADMISSION_PROPERTY) != admission) {
                    throw new IllegalStateException("matrix admission ownership changed");
                }
                admission.set(true);
                // A guard invocation racing arming cannot revive a rejected dependency.
                if (dependencyRejected.get()) {
                    admission.set(false);
                    throw new IllegalStateException("matrix dependencies changed during installation");
                }
            }
            installed = true;
        } catch (Exception | Error failed) {
            try { close(); } catch (Exception | Error cleanup) { failed.addSuppressed(cleanup); }
            throw failed;
        }
    }

    @Override public synchronized void close() {
        admission.set(false);
        installed = false;
        closed = true;
        if (properties != null) {
            synchronized (properties) {
                if (properties.get(MatrixScratchTransformer.ADMISSION_PROPERTY) == admission) properties.remove(MatrixScratchTransformer.ADMISSION_PROPERTY);
            }
        }
        if (registered.isEmpty()) return;
        restored = false;
        IllegalStateException failure = null;
        for (ClassFileTransformer owned : List.copyOf(registered)) {
            try {
                if (!instrumentation.removeTransformer(owned)) throw new IllegalStateException("matrix transformer removal not proven");
                registered.remove(owned);
            } catch (RuntimeException problem) {
                if (failure == null) failure = new IllegalStateException("matrix restoration failed");
                failure.addSuppressed(problem);
            }
        }
        if (registered.isEmpty()) {
            try {
                if (!originalTargetSha.equals(sha256(capture(target)))) throw new IllegalStateException("matrix original class restoration mismatch");
                restored = failure == null;
            } catch (Exception problem) {
                if (failure == null) failure = new IllegalStateException("matrix restoration failed");
                failure.addSuppressed(problem);
            }
        }
        if (failure != null) throw failure;
    }

    boolean restored() { return restored; }
}
