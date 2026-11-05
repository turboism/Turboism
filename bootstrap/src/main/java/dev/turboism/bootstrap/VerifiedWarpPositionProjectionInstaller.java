package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge;
import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/** Exact native pure-accessor/consumer admission and verified restoration. */
final class VerifiedWarpPositionProjectionInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.warp-position-projection";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.EXACTLY_17);

    private final Instrumentation instrumentation;
    private final Class<?> target;
    private final WarpPositionProjectionTransformer transformer;
    private final WarpPositionProjectionBridge bridge;
    private boolean installed, restored;

    private static final List<ReviewedHostContract.Candidate<String>> CANDIDATES = ReviewedHostContract.candidates(
            WarpPositionProjectionTransformer.reviewedClassSha256(), version -> HOOK_ID);

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested, int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    VerifiedWarpPositionProjectionInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader)
            throws Exception {
        if (Runtime.version().feature() != 17) {
            throw new IllegalArgumentException("warp projection requires JVM17");
        }
        final var contract = ReviewedHostContract.requireBound(
                ReviewedHostContract.resolve(artifact, CANDIDATES), "warp projection");
        this.instrumentation = instrumentation;
        if (!instrumentation.isRetransformClassesSupported())
            throw new IllegalStateException("retransform unavailable");
        target = Class.forName(WarpPositionProjectionTransformer.TARGET.replace('/', '.'), false, loader);
        Class<?> form = Class.forName("com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm", false, loader);
        Class<?> vector = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);
        Class<?> refs = Class.forName("com.live2d.cubism.doc.model.deformer.warp.WarpPointRef", false, loader);
        Class<?> helper = Class.forName("com.live2d.graphics3d.type.f", false, loader);
        Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            VerifiedHookInstallerSupport.attestClassSource(
                    target, loader, artifact, "warp dependency loader/source mismatch");
            transformer = new WarpPositionProjectionTransformer(
                    loader, artifact, VerifiedHookInstallerSupport.readReferenceBytes(jar, target));
            for (Method method : new Method[] {
                form.getMethod("getPositions"),
                form.getMethod("getSource"),
                form.getMethod("get_source$cubism"),
                form.getMethod("getAllPointRef"),
                refs.getMethod("getPos"),
                vector.getMethod("setX", float.class),
                vector.getMethod("setY", float.class),
                helper.getMethod("a", float[].class, int.class, int.class, vector, int.class, Object.class),
                helper.getMethod("a", float[].class, int.class, int.class, vector)
            }) {
                Class<?> owner = method.getDeclaringClass();
                VerifiedHookInstallerSupport.attestClassSource(
                        owner, loader, artifact, "warp dependency loader/source mismatch");
                verifyMethod(
                        jar,
                        owner,
                        method.getName(),
                        MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                                .descriptorString(),
                        observed);
            }
            VerifiedHookInstallerSupport.attestClassSource(
                    vector, loader, artifact, "warp dependency loader/source mismatch");
            verifyMethod(jar, vector, "<init>", "(FF)V", observed);
            verifyMethod(jar, vector, "<init>", "()V", observed);
            VerifiedHookInstallerSupport.attestClassSource(
                    refs, loader, artifact, "warp dependency loader/source mismatch");
            Class<?> source = form.getMethod("getSource").getReturnType();
            verifyMethod(
                    jar,
                    refs,
                    "<init>",
                    MethodType.methodType(void.class, source, int.class, form).descriptorString(),
                    observed);
            verifyMethod(jar, refs, "a", MethodType.methodType(void.class, form).descriptorString(), observed);
            verifyMethod(jar, refs, "a", "()I", observed);
        }
        bridge = new WarpPositionProjectionBridge(form, vector);
        contract.requireUnchanged(artifact);
    }

    private void verifyMethod(
            JarFile jar, Class<?> type, String name, String descriptor, Map<Class<?>, byte[]> observed)
            throws Exception {
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation, type, "warp dependency unmodifiable", "warp dependency inspection absent");
            observed.put(type, actual);
        }
        String owner = type.getName().replace('.', '/');
        var expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(jar, type), owner, name, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("warp dependency body changed: " + owner + "." + name);
        }
    }

    synchronized void install() throws Exception {
        if (installed) return;
        if (!instrumentation.isModifiableClass(target)) throw new IllegalStateException("warp consumer unmodifiable");
        bridge.install();
        try {
            instrumentation.addTransformer(transformer, true);
            installed = true;
            instrumentation.retransformClasses(target);
            if (transformer.matches() != 1 || transformer.failure() != null)
                throw new IllegalStateException("warp consumer not admitted: " + transformer.failure());
        } catch (Exception | Error failure) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(failure, this);
            throw failure;
        }
    }

    @Override
    public synchronized void close() {
        bridge.close();
        if (!installed) return;
        instrumentation.removeTransformer(transformer);
        try {
            byte[] original = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation, target, "warp dependency unmodifiable", "warp dependency inspection absent");
            String hash = VerifiedHookInstallerSupport.sha256Hex(original);
            restored = hash.equals(transformer.beforeSha256());
            if (!restored) throw new IllegalStateException("native warp consumer restoration not proven");
            installed = false;
        } catch (Exception failure) {
            throw new IllegalStateException("warp restoration failed", failure);
        }
    }

    boolean restored() {
        return restored;
    }
}
