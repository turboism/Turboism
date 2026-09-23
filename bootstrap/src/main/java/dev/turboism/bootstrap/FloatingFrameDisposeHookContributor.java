package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.VerifiedEmbeddedPanelResolverFactory;

/** Declarative contributor for the verified floating-frame dispose hook. */
final class FloatingFrameDisposeHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_FLOATING_FRAME_DISPOSE_HOOK";
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
                runtime.info("bootstrap", "Turboism floating-frame dispose hook skipped in safe mode"));
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
        final VerifiedFloatingFrameDisposeHookInstaller installer =
            new VerifiedFloatingFrameDisposeHookInstaller(
                environment.instrumentation(),
                resolver.verifiedSelector("cubism.ui-panel.palette-frame.raw-disposed"),
                host.classLoader()
            );
        installer.install();
        environment.runtime().ifPresent(runtime ->
            runtime.info("bootstrap", "Turboism floating-frame dispose hook installed"));
        return installer;
    }
}
