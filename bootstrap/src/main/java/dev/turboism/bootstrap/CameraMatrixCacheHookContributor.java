package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for the root-camera world-to-camera matrix cache.
 */
final class CameraMatrixCacheHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_CAMERA_MATRIX_CACHE";

    CameraMatrixCacheHookContributor() {
        super(PROTOCOL_ID, VerifiedCameraMatrixCacheInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.CAMERA_MATRIX_CACHE)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedCameraMatrixCacheInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedCameraMatrixCacheInstaller installer =
                new VerifiedCameraMatrixCacheInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " cameraMatrixCache=ACTIVE");
        return installer;
    }
}
