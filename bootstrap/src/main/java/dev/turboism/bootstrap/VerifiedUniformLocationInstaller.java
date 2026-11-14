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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Target-verified installation with an independently pinned JOGL artifact.
 *
 * <p>One shader-query class and the four lifecycle roles are prepared and attested here — in
 * registration order, shader query first — and then handed to {@link VerifiedInstall}, which publishes
 * the callbacks, registers every transformer, retransforms the five classes in one call, proves one
 * rewritten site per class and re-captures each of them on close. An installation or restoration
 * failure never becomes a silent success.</p>
 *
 * <p>The preparation stays in the constructor on purpose: the restored baseline of every class is the
 * definition this installer <em>observes</em>, which may already be composed by the shader-uniform or
 * glGetError-elision hook, so the digest the close proof must reproduce is fixed before anything is
 * registered.</p>
 */
final class VerifiedUniformLocationInstaller implements AutoCloseable {
    static final String HOOK_ID = "cubism.render.uniform-location-cache";

    /** The admission row this hook froze: policy id, kill-switch semantics and JVM predicate. */
    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    /** Each of the five transformers rewrites exactly one site; pinned by {@code WeaveSpecTest}. */
    static final int EXPECTED_MATCHES = 1;

    /**
     * The shader query and the four lifecycle roles are all re-captured on close; pinned by
     * {@code WeaveSpecTest}.
     */
    static final int EXPECTED_OWNERS = 5;

    private static final List<ReviewedHostContract.Candidate<Map<UniformLocationLifecycleTransformer.Role, String>>>
            CANDIDATES = ReviewedHostContract.candidates(
                    UniformLocationLifecycleTransformer.reviewedClassSha256(), version -> {
                        final Map<UniformLocationLifecycleTransformer.Role, String> owners = new HashMap<>();
                        for (final var role : UniformLocationLifecycleTransformer.Role.values()) {
                            owners.put(role, role.ownerForVersion(version));
                        }
                        return Map.copyOf(owners);
                    });

    private final InstallContext context;
    private final UniformLocationHookBridge bridge;
    /** Registration order, which is load-bearing: the shader query first, then {@code Role.values()}. */
    private final List<WeaveTarget> targets = new ArrayList<>();

    private final boolean seamComposed;
    private boolean closed;
    private VerifiedInstall<ReviewedHostContract.Bound<Map<UniformLocationLifecycleTransformer.Role, String>>> hook;

    VerifiedUniformLocationInstaller(final InstallContext context) throws Exception {
        this.context = context;
        final Path artifact = context.artifact();
        final ClassLoader loader = context.hostLoader();
        final var contract =
                ReviewedHostContract.requireBound(ReviewedHostContract.resolve(artifact, CANDIDATES), "uniform cache");
        final String editorVersion = contract.sourceVersion();
        final HostArtifactDigest editor = HostArtifactDigest.cached(artifact);
        if (context.jvmFeature() < 17 || !context.instrumentation().isRetransformClassesSupported()) {
            throw new IllegalStateException("uniform cache requires JVM17+ retransformation");
        }
        Path joglArtifact = artifact.toAbsolutePath()
                .getParent()
                .resolve("jogl/jogl-all.jar")
                .normalize();
        if (!ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(HostArtifactDigest.cached(joglArtifact))) {
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
                    context.instrumentation(),
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
            targets.add(WeaveTarget.single(shader, query, EXPECTED_MATCHES, query::matches, query::failure));
            for (var role : UniformLocationLifecycleTransformer.Role.values()) {
                Class<?> type =
                        Class.forName(role.ownerForVersion(editorVersion).replace('/', '.'), false, loader);
                Path source = role.programMutations() ? joglArtifact : artifact;
                ClassLoader ownerLoader = role.programMutations() ? type.getClassLoader() : loader;
                VerifiedHookInstallerSupport.attestClassSource(
                        type, ownerLoader, source, "uniform dependency loader/source mismatch: " + type.getName());
                byte[] actual = VerifiedHookInstallerSupport.captureLoadedBytes(
                        context.instrumentation(),
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
                targets.add(WeaveTarget.single(
                        type, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
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

    static boolean admitted(Path artifact, RuntimeStartupConfig config, boolean requested, int jvm) {
        return VerifiedInstall.admits(
                ADMISSION, config, requested, jvm, () -> ReviewedHostContract.resolved(artifact, CANDIDATES));
    }

    private static Optional<ReviewedHostContract.Bound<Map<UniformLocationLifecycleTransformer.Role, String>>> resolve(
            final Path artifact) {
        return ReviewedHostContract.resolve(artifact, CANDIDATES)
                        instanceof
                        ReviewedHostContract.Bound<Map<UniformLocationLifecycleTransformer.Role, String>> bound
                ? Optional.of(bound)
                : Optional.empty();
    }

    /**
     * @return the hook's spec: the targets were attested and their restoration baselines captured by
     *     the constructor, so the plan only hands them over in registration order
     */
    private WeaveSpec<ReviewedHostContract.Bound<Map<UniformLocationLifecycleTransformer.Role, String>>> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                "uniform location cache",
                VerifiedUniformLocationInstaller::resolve,
                new WeavePlan<>((contract, source) -> List.copyOf(targets), WeavePlan.PostCheck.NONE),
                (contract, launch) -> BridgeLayer.of(this::publish, bridge),
                WeaveEffects.none());
    }

    /** Confirms the attested mutation coverage and then publishes every owned slot. */
    private void publish() {
        bridge.confirmMutationCoverage();
        bridge.install();
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
                    context.instrumentation(),
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

    /**
     * Binds the hook and installs the weave through {@link VerifiedInstall}. The handle is kept before
     * the weave runs, so a failed installation can still prove the restoration of the classes it
     * rewrote.
     *
     * @throws IllegalStateException when the installer is closed or the artifact is not an admitted
     *     uniform-cache host
     * @throws Exception when registration, retransformation or the rewrite proof fails
     */
    synchronized void install() throws Exception {
        if (closed) throw new IllegalStateException("uniform installer is closed");
        if (hook != null) return;
        if (!(VerifiedInstall.bind(spec(), context)
                instanceof
                VerifiedInstall.Attempt.Installed<
                                ReviewedHostContract.Bound<Map<UniformLocationLifecycleTransformer.Role, String>>>
                        installed)) {
            throw new IllegalStateException("uniform cache refused: host-not-supported-or-hook-disabled");
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
