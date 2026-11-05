package dev.turboism.bootstrap;

/** Contributor for the verified unchanged-frame model update skip optimization. */
final class ModelUpdateSkipHookContributor extends NativeOptimizationHookContributor {

    ModelUpdateSkipHookContributor() {
        super("TURBOISM_MODEL_UPDATE_SKIP", VerifiedModelUpdateSkipInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!NativeOptimizationPolicy.modelUpdateSkipEnabled(environment)) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        // The launch startup policy snapshot, never a re-read of config.json: a hook that re-read the
        // home directory could admit differently from the agent that gated it.
        if (!VerifiedModelUpdateSkipInstaller.admitted(
                host.artifact(),
                environment.plan().policy(),
                true,
                Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedModelUpdateSkipInstaller installer =
                new VerifiedModelUpdateSkipInstaller(InstallContext.of(environment));
        installer.install();
        return installer;
    }
}
