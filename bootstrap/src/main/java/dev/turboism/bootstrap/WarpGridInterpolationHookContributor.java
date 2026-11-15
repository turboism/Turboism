package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributes the warp grid point-interpolation optimization hook when admitted by runtime policy.
 */
final class WarpGridInterpolationHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_WARP_GRID_INTERPOLATION";
    private static final String REFUSED_HOST_UNSUPPORTED = "host-not-supported-or-hook-disabled";
    private static final String STATUS_ACTIVE = " warpGridInterpolation=ACTIVE";
    private static final String STATUS_NOT_ADMITTED = " installation=NOT_ADMITTED";

    WarpGridInterpolationHookContributor() {
        super(PROTOCOL_ID, VerifiedWarpGridInterpolationInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.WARP_GRID_INTERPOLATION)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedWarpGridInterpolationInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(REFUSED_HOST_UNSUPPORTED);
        }
        final VerifiedWarpGridInterpolationInstaller installer =
                new VerifiedWarpGridInterpolationInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + STATUS_ACTIVE);
        return installer;
    }
}
