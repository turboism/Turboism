package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.scenehandler.SceneHandlerCacheBridge;
import dev.turboism.adapter.cubism.optimization.scenehandler.SceneHandlerCacheTarget;
import dev.turboism.adapter.cubism.optimization.scenehandler.SceneHandlerCacheTransformer;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.mapping.verification.HostArtifactDigest;
import java.io.IOException;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Installs and restores the verified scene mouse-handler traversal cache and selective invalidator.
 */
final class VerifiedSceneHandlerCacheInstaller implements AutoCloseable {

    static final String HOOK_ID = "cubism.input.scene-handler-cache";

    static final Admission ADMISSION = new Admission(HOOK_ID, PolicyCheck.REQUIRED, JvmCheck.AT_LEAST_17);

    static final int EXPECTED_MATCHES = 3;

    static final int EXPECTED_OWNERS = 3;

    private static final int EXPECTED_SITES = 8;

    private final InstallContext context;
    private VerifiedInstall<SceneHandlerCacheTarget> hook;

    VerifiedSceneHandlerCacheInstaller(final InstallContext context) {
        this.context = context;
    }

    static boolean admitted(
            final Path artifact, final RuntimeStartupConfig config, final boolean requested, final int jvm)
            throws IOException {
        return VerifiedInstall.admits(ADMISSION, config, requested, jvm)
                && SceneHandlerCacheTarget.of(HostArtifactDigest.cached(artifact))
                        .isPresent();
    }

    private WeaveSpec<SceneHandlerCacheTarget> spec() {
        return new WeaveSpec<>(
                ADMISSION,
                "scene handler cache",
                VerifiedSceneHandlerCacheInstaller::resolve,
                new WeavePlan<>(
                        VerifiedSceneHandlerCacheInstaller::targets,
                        VerifiedSceneHandlerCacheInstaller::sitesProven),
                (target, launch) -> {
                    final SceneHandlerCacheBridge bridge =
                            new SceneHandlerCacheBridge(launch.hostLoader());
                    return BridgeLayer.of(bridge::install, bridge);
                },
                WeaveEffects.none());
    }

    private static Optional<SceneHandlerCacheTarget> resolve(final Path artifact) throws Exception {
        return SceneHandlerCacheTarget.of(HostArtifactDigest.cached(artifact));
    }

    private static List<WeaveTarget> targets(final SceneHandlerCacheTarget target, final WeaveSource source)
            throws Exception {
        final Class<?> viewOwner = source.load(SceneHandlerCacheTarget.VIEW_CONTEXT_OWNER);
        final Class<?> entitiesOwner = source.load(SceneHandlerCacheTarget.ENTITIES_OWNER);
        final Class<?> componentsOwner = source.load(SceneHandlerCacheTarget.COMPONENTS_OWNER);
        source.load(SceneHandlerCacheTarget.MOUSE_HANDLER_OWNER);
        for (final SceneHandlerCacheTarget.Dep dep : target.dependencies()) {
            verifyMethodPresent(source.load(dep.owner()), dep.name(), dep.descriptor());
        }
        final SceneHandlerCacheTransformer transformer = new SceneHandlerCacheTransformer(
                source.loader(),
                source.artifact(),
                target,
                source.referenceBytes(viewOwner),
                source.referenceBytes(entitiesOwner),
                source.referenceBytes(componentsOwner));
        return List.of(
                new WeaveTarget(
                        viewOwner,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure),
                new WeaveTarget(
                        entitiesOwner,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure),
                new WeaveTarget(
                        componentsOwner,
                        transformer,
                        EXPECTED_MATCHES,
                        transformer::matches,
                        transformer::failure));
    }

    private static String sitesProven(final List<WeaveTarget> targets) {
        final int sites = targets.get(0)
                .transformer(SceneHandlerCacheTransformer.class)
                .sites();
        return sites == EXPECTED_SITES ? null : "sites=" + sites;
    }

    private static void verifyMethodPresent(final Class<?> owner, final String name, final String descriptor)
            throws NoSuchMethodException {
        for (final Method method : owner.getMethods()) {
            if (!method.getName().equals(name)) continue;
            final String actual = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                    .descriptorString();
            if (actual.equals(descriptor)) return;
        }
        throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
    }

    synchronized void install() throws Exception {
        if (hook != null) return;
        if (!(VerifiedInstall.install(spec(), context)
                instanceof VerifiedInstall.Attempt.Installed<SceneHandlerCacheTarget> installed)) {
            throw new IllegalStateException("scene handler cache refused: host-not-supported-or-hook-disabled");
        }
        hook = installed.hook();
    }

    @Override
    public synchronized void close() {
        if (hook != null) hook.close();
    }

    boolean restored() {
        return hook != null && hook.restored();
    }
}
