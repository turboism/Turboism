package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.geometry.WarpPositionProjectionBridge;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Contributor for the verified warp position projection optimization. */
final class WarpPositionProjectionHookContributor extends NativeOptimizationHookContributor {

    WarpPositionProjectionHookContributor() {
        super("TURBOISM_WARP_POSITION_PROJECTION");
    }

    @Override AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!Boolean.getBoolean(WarpPositionProjectionBridge.ENABLE_PROPERTY)) {
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedWarpPositionProjectionInstaller.admitted(
            HostArtifactDigest.from(host.artifact()),
            NativeOptimizationPolicy.load(environment.options().home()),
            true,
            Runtime.version().feature()
        )) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedWarpPositionProjectionInstaller installer =
            new VerifiedWarpPositionProjectionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
