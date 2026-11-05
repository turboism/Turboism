package dev.turboism.bootstrap;

/** Contributor for the verified reinit Pair-allocation elision optimization. */
final class ReinitPairElisionHookContributor extends NativeOptimizationHookContributor {

    ReinitPairElisionHookContributor() {
        super("TURBOISM_REINIT_PAIR_ELISION", VerifiedReinitPairElisionInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.reinitPairElisionEnabled(environment)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        // The launch startup policy snapshot, never a re-read of config.json: a hook that re-read the
        // home directory could admit differently from the agent that gated it.
        if (!VerifiedReinitPairElisionInstaller.admitted(host.artifact(), environment.startupPolicy(), true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedReinitPairElisionInstaller installer =
                new VerifiedReinitPairElisionInstaller(InstallContext.of(environment));
        installer.install();
        return installer;
    }
}
