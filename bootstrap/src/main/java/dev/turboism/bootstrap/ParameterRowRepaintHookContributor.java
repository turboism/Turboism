package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for incremental Parameter Palette row updates and Swing repaint elision.
 */
final class ParameterRowRepaintHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_PARAMETER_ROW_REPAINT";
    private static final String STATUS_NOT_ADMITTED = " installation=NOT_ADMITTED";
    private static final String STATUS_ACTIVE = " parameterRowRepaint=ACTIVE";

    ParameterRowRepaintHookContributor() {
        super(PROTOCOL_ID, VerifiedParameterRowRepaintInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.PARAMETER_ROW_REPAINT)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedParameterRowRepaintInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedParameterRowRepaintInstaller installer =
                new VerifiedParameterRowRepaintInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + STATUS_ACTIVE);
        return installer;
    }
}
