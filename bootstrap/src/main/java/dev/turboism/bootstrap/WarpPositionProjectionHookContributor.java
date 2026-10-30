package dev.turboism.bootstrap;

/** Contributor for the verified warp position projection optimization. */
final class WarpPositionProjectionHookContributor extends NativeOptimizationHookContributor {

    WarpPositionProjectionHookContributor() {
        super("TURBOISM_WARP_POSITION_PROJECTION", VerifiedWarpPositionProjectionInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.warpPositionProjectionEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedWarpPositionProjectionInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedWarpPositionProjectionInstaller installer = new VerifiedWarpPositionProjectionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
