package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.optimization.inspector.InspectorPropertyBypassBridge;

/**
 * Contributor for the verified inspector visual-property bypass.
 *
 * <p>Opt-in through the persisted {@code inspectorPropertyBypass} preference or
 * {@code -Dturboism.optimization.inspectorPropertyBypass=true}. It installs in the
 * host-resolved phase so the first inspector property change already benefits.</p>
 */
final class InspectorPropertyBypassHookContributor extends NativeOptimizationHookContributor {

    InspectorPropertyBypassHookContributor() {
        super("TURBOISM_INSPECTOR_PROPERTY_BYPASS", VerifiedInspectorPropertyBypassInstaller.HOOK_ID);
    }

    @Override
    AutoCloseable installAdmitted(final HookEnvironment environment) throws Exception {
        final boolean validation = Boolean.getBoolean(InspectorPropertyBypassBridge.VALIDATION_PROPERTY);
        final boolean production = NativeOptimizationPolicy.inspectorPropertyBypassEnabled(environment);
        if (!validation && !production) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("preference-disabled");
        }
        final var host = environment.host().orElseThrow();
        if (!VerifiedInspectorPropertyBypassInstaller.admitted(
                host.artifact(), true, Runtime.version().feature())) {
            debug(environment, id() + " installation=NOT_ADMITTED");
            return refused("host-not-supported-or-hook-disabled");
        }
        final var installer = new VerifiedInspectorPropertyBypassInstaller(
                environment.instrumentation(), host.artifact(), host.classLoader());
        installer.install();
        log(
                environment,
                id() + " installation=COMPLETE version=" + installer.cubismVersion() + " sites=" + installer.sites()
                        + " stats=" + installer.snapshot()
                        + (installer.failure() == null ? "" : " refused=" + installer.failure()));
        return () -> {
            final var stats = installer.snapshot();
            installer.close();
            log(environment, id() + " installation=CLOSED stats=" + stats);
        };
    }
}
