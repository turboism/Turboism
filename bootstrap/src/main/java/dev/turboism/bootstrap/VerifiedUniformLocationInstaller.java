package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationCallSiteTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/**
 * Exact opt-in installation for one reviewed Editor and its bundled JOGL artifact.
 * Callbacks are published only after all required transforms succeed. Closing disables
 * reuse first, removes every owned transformer and verifies all original class
 * digests. An installation or restoration failure never becomes a silent success.
 */
final class VerifiedUniformLocationInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.render.uniform-location-cache";
    private final Instrumentation instrumentation;
    private final UniformLocationHookBridge bridge;
    private final List<Target> targets = new ArrayList<>();
    private final List<Target> registered = new ArrayList<>();
    private boolean installed, restored, closed;

    private record Target(Class<?> type, ClassFileTransformer transformer, String before,
                          IntSupplier matches, Supplier<String> failure) { }

    static boolean admitted(HostArtifactDigest digest, RuntimeStartupConfig config, boolean requested, int jvm) {
        return requested && jvm >= 17 && config.hookEnabled(HOOK_ID)
            && ReviewedHostArtifacts.CUBISM_5_3_03.equals(digest);
    }
    VerifiedUniformLocationInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader) throws Exception {
        if (!ReviewedHostArtifacts.CUBISM_5_3_03.equals(HostArtifactDigest.from(artifact))) {
            throw new IllegalArgumentException("uniform cache requires the reviewed 5.3.03 Editor");
        }
        if (Runtime.version().feature() < 17 || !instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("uniform cache requires JVM17+ retransformation");
        }
        this.instrumentation = instrumentation;
        Path joglArtifact = artifact.toAbsolutePath().getParent().resolve("jogl/jogl-all.jar").normalize();
        if (!ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(HostArtifactDigest.from(joglArtifact))) {
            throw new IllegalArgumentException("uniform cache bundled JOGL identity mismatch");
        }
        bridge = new UniformLocationHookBridge(loader);
        Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile cubism = new JarFile(artifact.toFile()); JarFile jogl = new JarFile(joglArtifact.toFile())) {
            verifyMutationInventory(jogl);
            Class<?> shader = Class.forName(UniformLocationCallSiteTransformer.OWNER.replace('/', '.'), false, loader);
            attest(shader, loader, artifact);
            byte[] before = capture(shader); observed.put(shader, before);
            verify(cubism, shader, before, UniformLocationCallSiteTransformer.METHOD, UniformLocationCallSiteTransformer.DESCRIPTOR);
            UniformLocationCallSiteTransformer query = new UniformLocationCallSiteTransformer(loader, artifact, reference(cubism, shader));
            targets.add(new Target(shader, query, sha256(before), query::matches, query::failure));
            for (var role : UniformLocationLifecycleTransformer.Role.values()) {
                Class<?> type = Class.forName(role.owner().replace('/', '.'), false, loader);
                Path source = role.programMutations() ? joglArtifact : artifact;
                ClassLoader ownerLoader = role.programMutations() ? type.getClassLoader() : loader;
                attest(type, ownerLoader, source);
                byte[] actual = capture(type); observed.put(type, actual);
                JarFile jar = role.programMutations() ? jogl : cubism;
                for (var method : role.methods().entrySet()) verify(jar, type, actual, method.getKey(), method.getValue());
                UniformLocationLifecycleTransformer transformer = new UniformLocationLifecycleTransformer(ownerLoader, source, reference(jar, type), role);
                transformer.onRejection(bridge::retire);
                targets.add(new Target(type, transformer, sha256(actual), transformer::matches, transformer::failure));
            }
            verifyDependency(cubism, artifact, loader, "com.live2d.graphics3d.a", "a", "()Lcom/jogamp/opengl/GL3;", observed);
            verifyDependency(jogl, joglArtifact, loader, "jogamp.opengl.gl4.GL4bcImpl", "getContext", "()Lcom/jogamp/opengl/GLContext;", observed);
            verifyDependency(jogl, joglArtifact, loader, "com.jogamp.opengl.GLContext", "getCurrent", "()Lcom/jogamp/opengl/GLContext;", observed);
            verifyDependency(jogl, joglArtifact, loader, "com.jogamp.opengl.GLContext", "isShared", "()Z", observed);
            verifyDependency(jogl, joglArtifact, loader, "com.jogamp.opengl.GLContext", "isCreated", "()Z", observed);
        }
    }
    static void verifyMutationInventory(JarFile jar) throws Exception {
        UniformLocationLifecycleTransformer.verifyMutationInventory(jar);
    }
    private void verifyDependency(JarFile jar, Path source, ClassLoader lookupLoader, String name,
                                  String method, String descriptor, Map<Class<?>, byte[]> observed) throws Exception {
        Class<?> type = Class.forName(name, false, lookupLoader);
        attest(type, type.getClassLoader(), source);
        byte[] actual = observed.get(type);
        if (actual == null) { actual = capture(type); observed.put(type, actual); }
        verify(jar, type, actual, method, descriptor);
    }
    private static void attest(Class<?> type, ClassLoader loader, Path artifact) throws Exception {
        if (type.getClassLoader() != loader || type.getProtectionDomain().getCodeSource() == null
            || !Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize()
                .equals(artifact.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("uniform dependency loader/source mismatch: " + type.getName());
        }
    }
    private static void verify(JarFile jar, Class<?> type, byte[] actual, String name, String descriptor) throws Exception {
        String owner = type.getName().replace('.', '/');
        List<String> expected = ReviewedMethodShape.read(reference(jar, type), owner, name, descriptor);
        if (expected == null || !expected.equals(ReviewedMethodShape.read(actual, owner, name, descriptor))) {
            throw new IllegalStateException("uniform dependency body mismatch: " + owner + "." + name);
        }
    }
    private static byte[] reference(JarFile jar, Class<?> type) throws Exception {
        var entry = jar.getJarEntry(type.getName().replace('.', '/') + ".class");
        if (entry == null) throw new IllegalArgumentException("uniform reference class missing: " + type.getName());
        try (var input = jar.getInputStream(entry)) { return input.readAllBytes(); }
    }
    private byte[] capture(Class<?> type) throws Exception {
        if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException("uniform target is not modifiable: " + type.getName());
        AtomicReference<byte[]> captured = new AtomicReference<>();
        ClassFileTransformer observer = new ClassFileTransformer() {
            @Override public byte[] transform(Module module, ClassLoader loader, String name, Class<?> redefined,
                                              ProtectionDomain domain, byte[] bytes) {
                if (redefined == type) captured.set(bytes.clone());
                return null;
            }
        };
        instrumentation.addTransformer(observer, true);
        try { instrumentation.retransformClasses(type); }
        finally { instrumentation.removeTransformer(observer); }
        if (captured.get() == null) throw new IllegalStateException("uniform class capture absent: " + type.getName());
        return captured.get();
    }
    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    synchronized void install() throws Exception {
        if (closed) throw new IllegalStateException("uniform installer is closed");
        if (installed) return;
        try {
            for (Target target : targets) {
                instrumentation.addTransformer(target.transformer(), true);
                registered.add(target);
            }
            instrumentation.retransformClasses(targets.stream().map(Target::type).toArray(Class<?>[]::new));
            for (Target target : targets) if (target.matches().getAsInt() != 1 || target.failure().get() != null) {
                throw new IllegalStateException("uniform transform rejected: " + target.type().getName() + ": " + target.failure().get());
            }
            bridge.confirmMutationCoverage();
            bridge.install();
            installed = true;
        } catch (Exception | Error problem) {
            try { close(); } catch (Exception | Error cleanup) { problem.addSuppressed(cleanup); }
            throw problem;
        }
    }
    @Override public synchronized void close() {
        bridge.close(); closed = true; installed = false;
        if (registered.isEmpty()) return;
        List<Target> removing = List.copyOf(registered);
        IllegalStateException failure = null;
        for (Target target : removing) {
            if (!instrumentation.removeTransformer(target.transformer())) {
                if (failure == null) failure = new IllegalStateException("uniform transformer removal not proven");
                failure.addSuppressed(new IllegalStateException(target.type().getName()));
            }
        }
        registered.clear();
        for (Target target : removing) {
            try {
                String actual = sha256(capture(target.type()));
                if (!actual.equals(target.before())) throw new IllegalStateException("uniform class restoration mismatch: " + target.type().getName());
            } catch (Exception problem) {
                if (failure == null) failure = new IllegalStateException("uniform native restoration failed");
                failure.addSuppressed(problem);
            }
        }
        restored = failure == null;
        if (failure != null) throw failure;
    }
    boolean restored() { return restored; }
}
