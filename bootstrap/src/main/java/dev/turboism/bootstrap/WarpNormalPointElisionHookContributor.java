package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for eliding disabled outside-of-parent warp control-point boxing and buffer uploads.
 */
final class WarpNormalPointElisionHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_WARP_NORMAL_POINT_ELISION";

    WarpNormalPointElisionHookContributor() {
        super(PROTOCOL_ID, VerifiedWarpNormalPointElisionInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.WARP_NORMAL_POINT_ELISION)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedWarpNormalPointElisionInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedWarpNormalPointElisionInstaller installer =
                new VerifiedWarpNormalPointElisionInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " warpNormalPointElision=ACTIVE");
        return installer;
    }
}
