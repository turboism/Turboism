package dev.turboism.bootstrap;

/** Contributor for the verified incremental model update optimization. */
final class IncrementalUpdateHookContributor extends NativeOptimizationHookContributor {

    IncrementalUpdateHookContributor() {
        super("TURBOISM_INCREMENTAL_UPDATE", VerifiedIncrementalUpdateInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.incrementalUpdateEnabled(environment)) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedIncrementalUpdateInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedIncrementalUpdateInstaller installer = new VerifiedIncrementalUpdateInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
