package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.extendedinterpolation.ExtendedInterpolationSetupBridge;

/**
 * Contributor for the hierarchy-only incremental {@code CModelSource.setup()} optimization.
 *
 * <p>Enabled by default; {@code -Dturboism.optimization.extendedInterpolationIncrementalSetup=false}
 * opts out and {@code -Dturboism.validation.extendedInterpolationIncrementalSetup=true} forces it
 * for validation runs.</p>
 */
final class ExtendedInterpolationSetupHookContributor extends NativeOptimizationHookContributor {

    ExtendedInterpolationSetupHookContributor() {
        super("TURBOISM_EXTENDED_INTERPOLATION_INCREMENTAL_SETUP", VerifiedExtendedInterpolationSetupInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!ExtendedInterpolationSetupBridge.enabledByPreference()) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        final var installer = new VerifiedExtendedInterpolationSetupInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " incremental-setup=ACTIVE sites=" + installer.sites()
                        + " version=" + installer.cubismVersion()
                        + (installer.failure() == null ? "" : " refused=" + installer.failure()));
        return () -> {
            final java.util.Map<String, Long> stats = installer.snapshot();
            installer.close();
            log(environment, id() + " incremental-setup=CLOSED stats=" + stats);
        };
    }
}
