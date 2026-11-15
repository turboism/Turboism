package dev.turboism.bootstrap;

import dev.turboism.config.optimization.OptimizationCatalog;

/**
 * Contributor for the AWT cursor-validation and out-of-bounds pointer-query elision weave.
 */
final class CursorValidationElisionHookContributor extends NativeOptimizationHookContributor {

    private static final String PROTOCOL_ID = "TURBOISM_CURSOR_VALIDATION_ELISION";

    CursorValidationElisionHookContributor() {
        super(PROTOCOL_ID, VerifiedCursorValidationElisionInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!environment.plan().admitted(OptimizationCatalog.CURSOR_VALIDATION_ELISION)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(REFUSED_PREFERENCE_DISABLED);
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedCursorValidationElisionInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused(VerifiedInstall.ADMISSION_REFUSED);
        }
        final VerifiedCursorValidationElisionInstaller installer =
                new VerifiedCursorValidationElisionInstaller(InstallContext.of(environment));
        installer.install();
        log(environment, id() + " cursorValidateElision=ACTIVE pointerBoundsElision=ACTIVE");
        return installer;
    }
}
