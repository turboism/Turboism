package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.modelinstances.ModelInstanceCoalesceBridge;

/**
 * Contributor for the verified duplicate model-instance update coalescer.
 *
 * <p>The hook is enabled by default for an exactly admitted host and can be disabled
 * with {@code -Dturboism.optimization.modelInstanceCoalesce=false}. It installs in the
 * host-resolved phase so the initial document load already benefits.</p>
 */
final class ModelInstanceCoalesceHookContributor extends NativeOptimizationHookContributor {

    ModelInstanceCoalesceHookContributor() {
        super("TURBOISM_MODEL_INSTANCE_COALESCE", VerifiedModelInstanceCoalesceInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        if (!ModelInstanceCoalesceBridge.enabledByPreference()) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedModelInstanceCoalesceInstaller.admitted(
                host.artifact(), true, Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final VerifiedModelInstanceCoalesceInstaller installer = new VerifiedModelInstanceCoalesceInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " installation=COMPLETE version=" + installer.sourceVersion() + " stats="
                        + installer.snapshot());
        return installer;
    }
}
