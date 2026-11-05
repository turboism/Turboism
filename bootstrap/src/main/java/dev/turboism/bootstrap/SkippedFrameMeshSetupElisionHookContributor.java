package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.mapping.verification.HostArtifactDigest;

/**
 * Contributor for the skipped-frame {@code GMesh2.setup} elision.
 *
 * <p>Opt-in through the persisted {@code meshSetupElision} preference or
 * {@code -Dturboism.optimization.meshSetupElision=true}. It requires the
 * unchanged-frame model-update skip: without a skipped frame no mesh setup can
 * be proven redundant. That dependency is declared by the catalogue and decided
 * by the launch plan, so this contributor only reports the code it refuses with.
 * The bridge additionally requires every persistent buffer's own dirty flag to
 * be clear, so a newly created, reallocated or geometry-refreshed mesh always
 * runs its native setup.</p>
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
        if (!validation) {
            final var relation = environment.plan().relationRefusal(OptimizationCatalog.MESH_SETUP_ELISION);
            if (relation.isPresent()) {
                // Without skipped frames the consult can never elide; installing
                // would buy pure per-mesh overhead.
                debug(
                        environment,
                        id() + " installation=NOT_ADMITTED reason="
                                + relation.get().reasonCode());
                return refused(relation.get().reasonCode());
            }
        }
        final var host = environment.host().orElseThrow();
        // The launch startup policy snapshot, never a re-read of config.json: a hook that re-read the
        // home directory could admit differently from the agent that gated it.
        if (!VerifiedSkippedFrameMeshSetupElisionInstaller.admitted(
                HostArtifactDigest.from(host.artifact()),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedSkippedFrameMeshSetupElisionInstaller installer =
                new VerifiedSkippedFrameMeshSetupElisionInstaller(InstallContext.of(environment));
        installer.install();
        log(
                environment,
                id() + " elision=ACTIVE sites=" + installer.sites()
                        + " target=com/live2d/graphics3d/mesh/GMesh2.setup(Lcom/live2d/graphics3d/a;)V");
        return installer;
    }
}
