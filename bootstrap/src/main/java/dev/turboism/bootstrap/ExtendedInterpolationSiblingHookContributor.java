package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for constant-time sibling lookup and dirty-checked extended-interpolation updates.
 */
final class ExtendedInterpolationSiblingHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_EXTENDED_INTERPOLATION_SIBLING_ELISION";
    private static final String STATUS_NOT_ADMITTED = " installation=NOT_ADMITTED";
    private static final String STATUS_ACTIVE = " extendedInterpolationSiblingElision=ACTIVE";

    ExtendedInterpolationSiblingHookContributor() {
        super(PROTOCOL_ID, VerifiedExtendedInterpolationSiblingInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.EXTENDED_INTERPOLATION_SIBLING_ELISION)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedExtendedInterpolationSiblingInstaller.admitted(
                host.artifact(), environment.plan().policy(), true)) {
            debug(environment, id() + STATUS_NOT_ADMITTED);
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedExtendedInterpolationSiblingInstaller installer =
                new VerifiedExtendedInterpolationSiblingInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + STATUS_ACTIVE);
        return installer;
    }
}
