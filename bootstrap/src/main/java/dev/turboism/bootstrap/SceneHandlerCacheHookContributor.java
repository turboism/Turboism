package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for the depth-first scene mouse-handler cache and selective topology invalidation.
 */
final class SceneHandlerCacheHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_SCENE_HANDLER_CACHE";

    SceneHandlerCacheHookContributor() {
        super(PROTOCOL_ID, VerifiedSceneHandlerCacheInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.SCENE_HANDLER_CACHE)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedSceneHandlerCacheInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedSceneHandlerCacheInstaller installer =
                new VerifiedSceneHandlerCacheInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " sceneHandlerCache=ACTIVE selectiveInvalidation=ACTIVE");
        return installer;
    }
}
