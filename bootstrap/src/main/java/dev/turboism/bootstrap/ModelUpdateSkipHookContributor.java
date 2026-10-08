package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.modelupdate.ModelUpdateSkipBridge;

/** Contributor for the verified unchanged-frame model update skip optimization. */
final class ModelUpdateSkipHookContributor extends NativeOptimizationHookContributor {

    ModelUpdateSkipHookContributor() {
        super("TURBOISM_MODEL_UPDATE_SKIP", VerifiedModelUpdateSkipInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!ModelUpdateSkipBridge.flagEnabled()) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedModelUpdateSkipInstaller.admitted(
                host.artifact(),
                NativeOptimizationPolicy.load(environment.options().home()),
                true,
                Runtime.version().feature())) {
            log(environment, id() + " installation=NOT_ADMITTED");
            return noOp();
        }
        final VerifiedModelUpdateSkipInstaller installer = new VerifiedModelUpdateSkipInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        return installer;
    }
}
