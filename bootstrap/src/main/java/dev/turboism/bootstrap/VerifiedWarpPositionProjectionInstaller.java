package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge;
import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionTransformer;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarFile;

/** Exact native pure-accessor/consumer admission and verified restoration. */
final class VerifiedWarpPositionProjectionInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.warp-position-projection";
    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final WarpPositionProjectionTransformer transformer;
    private final WarpPositionProjectionBridge bridge;
    private boolean installed, restored;

    static boolean admitted(HostArtifactDigest digest, RuntimeStartupConfig config, boolean requested, int jvm) {
        return requested && jvm == 17 && config.hookEnabled(HOOK_ID) && ReviewedHostArtifacts.CUBISM_5_3_02.equals(digest);
    }

    VerifiedWarpPositionProjectionInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader) throws Exception {
        if (!ReviewedHostArtifacts.CUBISM_5_3_02.equals(HostArtifactDigest.from(artifact)) || Runtime.version().feature() != 17) {
            throw new IllegalArgumentException("warp projection requires exact Cubism 5.3.02/JVM17");
        }
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported()) throw new IllegalStateException("retransform unavailable");
        target = Class.forName(WarpPositionProjectionTransformer.TARGET.replace('/', '.'), false, loader);
        Class<?> form = Class.forName("com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm", false, loader);
        Class<?> vector = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);
        Class<?> refs = Class.forName("com.live2d.cubism.doc.model.deformer.warp.WarpPointRef", false, loader);
        Class<?> helper = Class.forName("com.live2d.graphics3d.type.f", false, loader);
        Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            attest(target, loader, artifact);
            transformer = new WarpPositionProjectionTransformer(loader, artifact, reference(jar, target));
            for (Method method : new Method[]{form.getMethod("getPositions"), form.getMethod("getSource"),
                form.getMethod("get_source$cubism"), form.getMethod("getAllPointRef"), refs.getMethod("getPos"),
                vector.getMethod("setX", float.class), vector.getMethod("setY", float.class),
                helper.getMethod("a", float[].class, int.class, int.class, vector, int.class, Object.class),
                helper.getMethod("a", float[].class, int.class, int.class, vector)}) {
                Class<?> owner = method.getDeclaringClass();
                attest(owner, loader, artifact);
                verifyMethod(jar, owner, method.getName(), MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString(), observed);
            }
            attest(vector, loader, artifact);
            verifyMethod(jar, vector, "<init>", "(FF)V", observed);
            verifyMethod(jar, vector, "<init>", "()V", observed);
            attest(refs, loader, artifact);
            Class<?> source = form.getMethod("getSource").getReturnType();
            verifyMethod(jar, refs, "<init>", MethodType.methodType(void.class, source, int.class, form).descriptorString(), observed);
            verifyMethod(jar, refs, "a", MethodType.methodType(void.class, form).descriptorString(), observed);
            verifyMethod(jar, refs, "a", "()I", observed);
        }
        bridge = new WarpPositionProjectionBridge(form, vector);
    }

    private static void attest(Class<?> type, ClassLoader loader, Path artifact) throws Exception {
        if (type.getClassLoader() != loader || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath().normalize().equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("warp dependency loader/source mismatch");
        }
    }

    private void verifyMethod(JarFile jar, Class<?> type, String name, String descriptor, Map<Class<?>, byte[]> observed) throws Exception {
        byte[] actual = observed.get(type);
        if (actual == null) { actual = capture(type); observed.put(type, actual); }
        String owner = type.getName().replace('.', '/');
        var expected = ReviewedMethodShape.read(reference(jar, type), owner, name, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("warp dependency body changed: " + owner + "." + name);
        }
    }

    private static byte[] reference(JarFile jar, Class<?> type) throws Exception {
        try (var input = jar.getInputStream(jar.getJarEntry(type.getName().replace('.', '/') + ".class"))) {
            return input.readAllBytes();
        }
    }

    private byte[] capture(Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException("warp dependency unmodifiable");
        AtomicReference<byte[]> result = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined, ProtectionDomain domain, byte[] bytes) {
                if (redefined == type) result.set(bytes.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try { instrumentation.retransformClasses(type); }
        finally { instrumentation.removeTransformer(observer); }
        if (result.get() == null) throw new IllegalStateException("warp dependency inspection absent");
        return result.get();
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(target)) throw new IllegalStateException("warp consumer unmodifiable");
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true); installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null) throw new IllegalStateException("warp consumer not admitted: " + transformer.failure());
        } catch (Exception | Error failure) {
            try { close(); } catch (Exception | Error cleanup) { failure.addSuppressed(cleanup); }
            bridge.close(); throw failure;
        }
    }

    @Override public synchronized void close() {
        bridge.close();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        try {
            byte[] original = capture(target);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
            restored = hash.equals(transformer.beforeSha256());
            if (!restored) throw new IllegalStateException("native warp consumer restoration not proven");
            installed = false;
        } catch (Exception failure) { throw new IllegalStateException("warp restoration failed", failure); }
    }
    boolean restored() { return restored; }
}
