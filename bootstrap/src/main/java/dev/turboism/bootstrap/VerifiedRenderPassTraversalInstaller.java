package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.renderpass.RenderPassTraversalBridge;
import dev.turboism.adapter.cubism.optimization.renderpass.RenderPassTraversalTarget;
import dev.turboism.adapter.cubism.optimization.renderpass.RenderPassTraversalTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified render-pass traversal and draw-label allocation weave.
 */
final class VerifiedRenderPassTraversalInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.render.render-pass-traversal";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = 1;

    static final int EXPECTED_OWNERS = 1;

    private static final String FEATURE_NAME = "render pass traversal";
    private static final String SITES_PREFIX = "sites=";
    private static final String REFUSED_MESSAGE = "render pass traversal refused: host-not-supported-or-hook-disabled";
    private static final String METHOD_SEPARATOR = ".";
    private static final String SHADER_DEFAULT_METHOD = "a";
    private static final String SHADER_DEFAULT_DESC_PREFIX = "(L";
    private static final String SHADER_DEFAULT_DESC_SUFFIX =
            ";Lcom/jogamp/opengl/GL;Ljava/lang/String;ZILjava/lang/Object;)I";

    static final WeaveSpec<RenderPassTraversalTarget> SPEC = new WeaveSpec<>(
            ADMISSION,
            FEATURE_NAME,
            VerifiedRenderPassTraversalInstaller::resolve,
            new WeavePlan<>(
                    VerifiedRenderPassTraversalInstaller::targets, VerifiedRenderPassTraversalInstaller::sitesProven),
            (target, launch) -> {
                final RenderPassTraversalBridge bridge = new RenderPassTraversalBridge();
                return BridgeLayer.of(bridge::install, bridge);
            },
            WeaveEffects.none());

    private final InstallContext context;
    private VerifiedInstall<RenderPassTraversalTarget> hook;

    VerifiedRenderPassTraversalInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && RenderPassTraversalTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private static Optional<RenderPassTraversalTarget> resolve(final Path artifact) throws Exception {
        return RenderPassTraversalTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final RenderPassTraversalTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> renderSystem = source.load(target.renderSystemOwner());
        source.load(RenderPassTraversalTarget.GROUP_RENDERER_OWNER);
        source.load(RenderPassTraversalTarget.CARRAY_LIST_OWNER);
        source.load(RenderPassTraversalTarget.SORTING_INFO_OWNER);
        final Class<?> shaderHelper = source.load(target.shaderHelperOwner());
        verifyMethodPresent(
                shaderHelper,
                SHADER_DEFAULT_METHOD,
                SHADER_DEFAULT_DESC_PREFIX + target.shaderHelperOwner() + SHADER_DEFAULT_DESC_SUFFIX);
        for (final RenderPassTraversalTarget.Dep dep : target.dependencies()) {
            verifyMethodPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final RenderPassTraversalTransformer transformer = new RenderPassTraversalTransformer(
                source.loader(), source.artifact(), source.referenceBytes(renderSystem), target);
        return List.of(WeaveTarget.single(
                renderSystem, transformer, EXPECTED_MATCHES, transformer::matches, transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final RenderPassTraversalTransformer transformer =
                targets.get(0).transformer(RenderPassTraversalTransformer.class);
        final int actual = transformer.sites();
        final int expected = transformer.expectedSites();
        return actual == expected ? null : SITES_PREFIX + actual;
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        if (matchesAnyMethod(owner.getMethods(), name, descriptor)
                || matchesAnyMethod(owner.getDeclaredMethods(), name, descriptor)) {
            return;
        }
        throw new NoSuchMethodException(owner.getName() + METHOD_SEPARATOR + name + descriptor);
    }

    private static boolean matchesAnyMethod(final Method[] methods, final String name, final String descriptor) {
        for (final Method method : methods) {
            if (!method.getName().equals(name)) {
                continue;
            }
            final String actual = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (actual.equals(descriptor)) {
                return true;
            }
        }
        return false;
    }

    synchronized void install() throws Exception {
        if (hook != null) {
            return;
        }
        if (!(VerifiedInstall.install(SPEC, context)
                instanceof VerifiedInstall.Attempt.Installed<RenderPassTraversalTarget> installed)) {
            throw new IllegalStateException(REFUSED_MESSAGE);
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) {
            hook.close();
        }
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
