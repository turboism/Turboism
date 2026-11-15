package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for allocation-free mesh bounding-box hit testing.
 */
final class MeshHitTestHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_MESH_HIT_TEST";
    private static final String STATUS_NOT_ADMITTED = " installation=NOT_ADMITTED";
    private static final String STATUS_ACTIVE = " meshHitTest=ACTIVE";

    MeshHitTestHookContributor() {
        super(PROTOCOL_ID, VerifiedMeshHitTestInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.MESH_HIT_TEST)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedMeshHitTestInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedMeshHitTestInstaller installer = new VerifiedMeshHitTestInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + STATUS_ACTIVE);
        return installer;
    }
}
