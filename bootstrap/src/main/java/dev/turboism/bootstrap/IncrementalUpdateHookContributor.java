package dev.turboism.bootstrap;

/** Contributor for the verified incremental model update optimization. */
final class IncrementalUpdateHookContributor extends NativeOptimizationHookContributor {

    IncrementalUpdateHookContributor() {
        super("TURBOISM_INCREMENTAL_UPDATE", VerifiedIncrementalUpdateInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.incrementalUpdateEnabled(environment)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        // The launch startup policy snapshot, never a re-read of config.json: a hook that re-read the
        // home directory could admit differently from the agent that gated it.
        if (!VerifiedIncrementalUpdateInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedIncrementalUpdateInstaller installer =
                new VerifiedIncrementalUpdateInstaller(InstallContext.of(environment));
        installer.install();
        return installer;
    }
}
