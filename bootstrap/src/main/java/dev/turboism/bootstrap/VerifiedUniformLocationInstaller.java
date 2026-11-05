package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTarget;
import dev.turboism.adapter.cubism.optimization.glerror.GlGetErrorElisionTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationCallSiteTransformer;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationHookBridge;
import dev.turboism.adapter.cubism.optimization.uniform.UniformLocationLifecycleTransformer;
import dev.turboism.adapter.cubism.shader.ShaderUniformTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.jar.JarFile;

/**
 * Target-verified installation with an independently pinned JOGL artifact.
 * Callbacks are published only after all required transforms succeed. Closing disables
 * reuse first, removes every owned transformer and verifies all original class
 * digests. An installation or restoration failure never becomes a silent success.
 */
final class VerifiedUniformLocationInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.render.uniform-location-cache";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    private final Instrumentation instrumentation;
    private final UniformLocationHookBridge bridge;
    private final List<Target> targets = new ArrayList<>();
    private final List<Target> registered = new ArrayList<>();
    private final boolean seamComposed;
    private boolean installed, restored, closed;

    private record Target(
            Class<?> type,
            ClassFileTransformer transformer,
            String before,
            IntSupplier matches,
            Supplier<String> failure) {}

    private static final List<ReviewedHostContract.Candidate<Map<UniformLocationLifecycleTransformer.Role, String>>>
            CANDIDATES = ReviewedHostContract.candidates(
                    UniformLocationLifecycleTransformer.reviewedClassSha256(), version -> {
                        final Map<UniformLocationLifecycleTransformer.Role, String> owners = new HashMap<>();
                        for (final var role : UniformLocationLifecycleTransformer.Role.values()) {
                            owners.put(role, role.ownerForVersion(version));
                        }
                        return Map.copyOf(owners);
                    });

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested, int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    VerifiedUniformLocationInstaller(Instrumentation instrumentation, Path artifact, ClassLoader loader)
            throws Exception {
        final var contract =
                ReviewedHostContract.requireBound(ReviewedHostContract.resolve(artifact, CANDIDATES), "uniform cache");
        final String editorVersion = contract.sourceVersion();
        final HostArtifactDigest editor = HostArtifactDigest.from(artifact);
        if (Runtime.version().feature() < 17 || !instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("uniform cache requires JVM17+ retransformation");
        }
        this.instrumentation = instrumentation;
        Path joglArtifact = artifact.toAbsolutePath()
                .getParent()
                .resolve("jogl/jogl-all.jar")
                .normalize();
        if (!ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(HostArtifactDigest.from(joglArtifact))) {
            throw new IllegalArgumentException("uniform cache bundled JOGL identity mismatch");
        }
        bridge = new UniformLocationHookBridge(loader);
        Map<Class<?>, byte[]> observed = new HashMap<>();
        try (JarFile cubism = new JarFile(artifact.toFile());
                JarFile jogl = new JarFile(joglArtifact.toFile())) {
            verifyMutationInventory(jogl);
            Class<?> shader = Class.forName(UniformLocationCallSiteTransformer.OWNER.replace('/', '.'), false, loader);
            VerifiedHookInstallerSupport.attestClassSource(
                    shader, loader, artifact, "uniform dependency loader/source mismatch: " + shader.getName());
            byte[] before = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    shader,
                    "uniform target is not modifiable: " + shader.getName(),
                    "uniform class capture absent: " + shader.getName());
            observed.put(shader, before);
            byte[] shaderReference = VerifiedHookInstallerSupport.readReferenceBytes(
                    cubism, shader, "uniform reference class missing: " + shader.getName());
            final List<String> composedQuery = composedQueryShape(loader, shaderReference);
            seamComposed = composedQuery != null;
            verify(
                    cubism,
                    shader,
                    before,
                    UniformLocationCallSiteTransformer.METHOD,
                    UniformLocationCallSiteTransformer.DESCRIPTOR,
                    composedQuery);
            UniformLocationCallSiteTransformer query =
                    new UniformLocationCallSiteTransformer(loader, artifact, shaderReference);
            if (composedQuery != null) {
                query.acceptComposedShape(
                        composedQuery,
                        ShaderUniformTransformer.BRIDGE_OWNER,
                        ShaderUniformTransformer.BRIDGE_LOOKUP,
                        ShaderUniformTransformer.BRIDGE_LOOKUP_DESCRIPTOR);
            }
            targets.add(new Target(
                    shader, query, VerifiedHookInstallerSupport.sha256Hex(before), query::matches, query::failure));
            for (var role : UniformLocationLifecycleTransformer.Role.values()) {
                Class<?> type =
                        Class.forName(role.ownerForVersion(editorVersion).replace('/', '.'), false, loader);
                Path source = role.programMutations() ? joglArtifact : artifact;
                ClassLoader ownerLoader = role.programMutations() ? type.getClassLoader() : loader;
                VerifiedHookInstallerSupport.attestClassSource(
                        type, ownerLoader, source, "uniform dependency loader/source mismatch: " + type.getName());
                byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                        instrumentation,
                        type,
                        "uniform target is not modifiable: " + type.getName(),
                        "uniform class capture absent: " + type.getName());
                observed.put(type, actual);
                JarFile jar = role.programMutations() ? jogl : cubism;
                Map<String, List<String>> composed = composedShapes(jar, type, source, ownerLoader, role, editor);
                for (var method : role.methods().entrySet())
                    verify(jar, type, actual, method.getKey(), method.getValue(), composed.get(method.getKey()));
                UniformLocationLifecycleTransformer transformer = new UniformLocationLifecycleTransformer(
                        ownerLoader,
                        source,
                        VerifiedHookInstallerSupport.readReferenceBytes(
                                jar, type, "uniform reference class missing: " + type.getName()),
                        role,
                        editorVersion);
                composed.forEach(transformer::acceptComposedShape);
                transformer.onRejection(bridge::retire);
                targets.add(new Target(
                        type,
                        transformer,
                        VerifiedHookInstallerSupport.sha256Hex(actual),
                        transformer::matches,
                        transformer::failure));
            }
            verifyDependency(
                    cubism, artifact, loader, "com.live2d.graphics3d.a", "a", "()Lcom/jogamp/opengl/GL3;", observed);
            verifyDependency(
                    jogl,
                    joglArtifact,
                    loader,
                    "jogamp.opengl.gl4.GL4bcImpl",
                    "getContext",
                    "()Lcom/jogamp/opengl/GLContext;",
                    observed);
            verifyDependency(
                    jogl,
                    joglArtifact,
                    loader,
                    "com.jogamp.opengl.GLContext",
                    "getCurrent",
                    "()Lcom/jogamp/opengl/GLContext;",
                    observed);
            verifyDependency(jogl, joglArtifact, loader, "com.jogamp.opengl.GLContext", "isShared", "()Z", observed);
            verifyDependency(jogl, joglArtifact, loader, "com.jogamp.opengl.GLContext", "isCreated", "()Z", observed);
        }
        contract.requireUnchanged(artifact);
    }

    static void verifyMutationInventory(JarFile jar) throws Exception {
        UniformLocationLifecycleTransformer.verifyMutationInventory(jar);
    }

    /**
     * Computes the exact method shape the reviewed shader-uniform rewrite leaves
     * on the official {@code preDraw_exe} body. That hook is registered
     * retransformation-incapable and owns the same query seam, so when it patched
     * this definition its output <em>is</em> the retransformation baseline this
     * installer observes. The shape is derived by running the shader hook's own
     * rewrite over the attested JAR reference, never by trusting the observed
     * bytes; a hook that did not patch this loader's definition, or a rewrite the
     * probe cannot reproduce, leaves the acceptance set empty and the strict
     * check unchanged.
     *
     * @return the composed shape, or {@code null} when nothing composed this seam
     */
    private static List<String> composedQueryShape(ClassLoader loader, byte[] reference) {
        if (!ShaderUniformTransformer.isPatched(loader, UniformLocationCallSiteTransformer.OWNER)) {
            return null;
        }
        return ShaderUniformTransformer.composedShape(reference);
    }

    /**
     * @return whether the shader query seam was admitted in its reviewed composed
     *     form, i.e. taken over from the shader-uniform hook
     */
    boolean seamComposed() {
        return seamComposed;
    }

    private void verifyDependency(
            JarFile jar,
            Path source,
            ClassLoader lookupLoader,
            String name,
            String method,
            String descriptor,
            Map<Class<?>, byte[]> observed)
            throws Exception {
        Class<?> type = Class.forName(name, false, lookupLoader);
        VerifiedHookInstallerSupport.attestClassSource(
                type, type.getClassLoader(), source, "uniform dependency loader/source mismatch: " + type.getName());
        byte[] actual = observed.get(type);
        if (actual == null) {
            actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                    instrumentation,
                    type,
                    "uniform target is not modifiable: " + type.getName(),
                    "uniform class capture absent: " + type.getName());
            observed.put(type, actual);
        }
        verify(jar, type, actual, method, descriptor, null);
    }
    /**
     * Computes the exact method shapes the reviewed glGetError elision produces
     * on the official bodies, for the ERROR role only. The elision transform is
     * registered upstream of this one in the retransform chain, so when it is
     * installed the ERROR owner legitimately arrives already elided; the shape
     * is derived by running the elision's own probe over the attested JAR
     * reference, never by trusting the observed chain output. An elision that
     * is not installed, an unreviewed host artifact, or a shape the probe
     * cannot produce all leave the acceptance set empty — the strict check
     * stays fail-closed.
     */
    private static Map<String, List<String>> composedShapes(
            JarFile jar,
            Class<?> type,
            Path source,
            ClassLoader ownerLoader,
            UniformLocationLifecycleTransformer.Role role,
            HostArtifactDigest editor)
            throws Exception {
        if (role != UniformLocationLifecycleTransformer.Role.ERROR) return Map.of();
        var elision = GlGetErrorElisionTarget.of(editor);
        // The marker is keyed by the elision target's own owner; admitting the
        // composed shape also requires this role's class to be that owner.
        if (elision.isEmpty()
                || !elision.get().owner().equals(role.owner(editor))
                || !GlGetErrorElisionTransformer.isInstalled(
                        ownerLoader, elision.get().owner())) {
            return Map.of();
        }
        Map<String, List<String>> composed = new HashMap<>();
        for (var method : role.methods().entrySet()) {
            if (!elision.get().method().equals(method.getKey())
                    || !elision.get().descriptor().equals(method.getValue())) {
                continue;
            }
            List<String> shape = GlGetErrorElisionTransformer.composedShape(
                    ownerLoader,
                    source,
                    VerifiedHookInstallerSupport.readReferenceBytes(
                            jar, type, "uniform reference class missing: " + type.getName()),
                    elision.get(),
                    type);
            if (shape != null) composed.put(method.getKey(), shape);
        }
        return composed;
    }

    private static void verify(
            JarFile jar, Class<?> type, byte[] actual, String name, String descriptor, List<String> composed)
            throws Exception {
        String owner = type.getName().replace('.', '/');
        List<String> expected = ReviewedMethodShape.read(
                VerifiedHookInstallerSupport.readReferenceBytes(
                        jar, type, "uniform reference class missing: " + type.getName()),
                owner,
                name,
                descriptor);
        List<String> observed = ReviewedMethodShape.read(actual, owner, name, descriptor);
        if (expected == null || (!expected.equals(observed) && (composed == null || !composed.equals(observed)))) {
            throw new IllegalStateException("uniform dependency body mismatch: " + owner + "." + name);
        }
    }

    synchronized void install() throws Exception {
        if (closed) throw new IllegalStateException("uniform installer is closed");
        if (installed) return;
        try {
            for (Target target : targets) {
                instrumentation.addTransformer(target.transformer(), true);
                registered.add(target);
            }
            instrumentation.retransformClasses(
                    targets.stream().map(Target::type).toArray(Class<?>[]::new));
            for (Target target : targets)
                if (target.matches().getAsInt() != 1 || target.failure().get() != null) {
                    throw new IllegalStateException("uniform transform rejected: "
                            + target.type().getName() + ": " + target.failure().get());
                }
            bridge.confirmMutationCoverage();
            bridge.install();
            installed = true;
        } catch (Exception | Error problem) {
            VerifiedHookInstallerSupport.closeOnInstallFailure(problem, this);
            throw problem;
        }
    }

    @Override
    public synchronized void close() {
        bridge.close();
        closed = true;
        installed = false;
        if (registered.isEmpty()) return;
        List<Target> removing = List.copyOf(registered);
        IllegalStateException failure = null;
        for (Target target : removing) {
            if (!instrumentation.removeTransformer(target.transformer())) {
                final IllegalStateException problem =
                        new IllegalStateException(target.type().getName());
                if (failure == null)
                    failure = new IllegalStateException("uniform transformer removal not proven", problem);
                else failure.addSuppressed(problem);
            }
        }
        registered.clear();
        for (Target target : removing) {
            try {
                String actual = VerifiedHookInstallerSupport.sha256Hex(VerifiedHookInstallerSupport.captureLoadedBytes(
                        instrumentation,
                        target.type(),
                        "uniform target is not modifiable: " + target.type().getName(),
                        "uniform class capture absent: " + target.type().getName()));
                if (!actual.equals(target.before()))
                    throw new IllegalStateException("uniform class restoration mismatch: "
                            + target.type().getName());
            } catch (Exception problem) {
                if (failure == null) failure = new IllegalStateException("uniform native restoration failed", problem);
                else failure.addSuppressed(problem);
            }
        }
        restored = failure == null;
        if (failure != null) throw failure;
    }

    boolean restored() {
        return restored;
    }
}
