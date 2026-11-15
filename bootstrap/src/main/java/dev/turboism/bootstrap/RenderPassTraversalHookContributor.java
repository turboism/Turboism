package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for allocation-free render-pass traversal and pre-cached draw debug labels.
 */
final class RenderPassTraversalHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_RENDER_PASS_TRAVERSAL";
    private static final String STATUS_NOT_ADMITTED = " installation=NOT_ADMITTED";
    private static final String STATUS_ACTIVE = " renderPassTraversal=ACTIVE";

    RenderPassTraversalHookContributor() {
        super(PROTOCOL_ID, VerifiedRenderPassTraversalInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.RENDER_PASS_TRAVERSAL)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedRenderPassTraversalInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedRenderPassTraversalInstaller installer =
                new VerifiedRenderPassTraversalInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + STATUS_ACTIVE);
        return installer;
    }
}
