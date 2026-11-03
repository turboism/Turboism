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
        if (!VerifiedReinitPairElisionInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedReinitPairElisionInstaller installer = new VerifiedReinitPairElisionInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
