package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory;

/** Declarative contributor for the verified floating-tab close hook. */
final class FloatingTabCloseHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_FLOATING_TAB_CLOSE_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        if (!environment.fullRuntimeAdmission()) {
            return false;
        }
        if (environment.safeMode()) {
            environment.runtime().ifPresent(runtime ->
                runtime.info("bootstrap", "Turboism floating-tab close hook skipped in safe mode"));
            return false;
        }
        return true;
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var host = environment.host().orElseThrow();
        final var resolver = new VerifiedEmbeddedPanelResolverFactory().create(
            environment.verificationRecord(
                "cubism-" + environment.profile() + "-ui-embedded-panel.json"
            ),
            host.artifact(),
            host.classLoader()
        );
        final VerifiedFloatingTabCloseHookInstaller installer =
            new VerifiedFloatingTabCloseHookInstaller(
                environment.instrumentation(),
                resolver.verifiedSelector("cubism.ui-panel.floating-tab-close.operation"),
                resolver.verifiedSelector("cubism.ui-panel.floating-tab-close.palette-field"),
                host.classLoader()
            );
        installer.install();
        environment.runtime().ifPresent(runtime ->
            runtime.info("bootstrap", "Turboism floating-tab close hook installed"));
        return installer;
    }
}
