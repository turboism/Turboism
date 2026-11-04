package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.HostArtifactDigest;

/**
 * Contributor for the skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>Opt-in through the persisted {@code meshSetupElision} preference or
 * {@code -Dturboism.optimization.meshSetupElision=true}. It requires the
 * unchanged-frame model-update skip: without a skipped frame no mesh setup can
 * be proven redundant. The bridge additionally requires every persistent
 * buffer's own dirty flag to be clear, so a newly created, reallocated or
 * geometry-refreshed mesh always runs its native setup.</p>
 */
final class SkippedFrameMeshSetupElisionHookContributor extends NativeOptimizationHookContributor {

    SkippedFrameMeshSetupElisionHookContributor() {
        super("TURBOISM_MESH_SETUP_ELISION", VerifiedSkippedFrameMeshSetupElisionInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean validation = Boolean.getBoolean("turboism.validation.meshSetupElision");
        if (!validation && !NativeOptimizationPolicy.meshSetupElisionEnabled(environment)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        if (!validation && !NativeOptimizationPolicy.modelUpdateSkipEnabled(environment)) {
            // Without skipped frames the consult can never elide; installing
            // would buy pure per-mesh overhead.
            debug(environment, id() + " installation=NOT_ADMITTED reason=modelUpdateSkip-disabled");
            return refused("modelUpdateSkip-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                HostArtifactDigest.from(host.artifact()),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedSkippedFrameMeshSetupElisionInstaller installer =
                new VerifiedSkippedFrameMeshSetupElisionInstaller(
                        environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " elision=ACTIVE sites=" + installer.sites()
                        + " target=com/live2d/graphics3d/mesh/GMesh2.setup(Lcom/live2d/graphics3d/a;)V");
        return installer;
    }
}
