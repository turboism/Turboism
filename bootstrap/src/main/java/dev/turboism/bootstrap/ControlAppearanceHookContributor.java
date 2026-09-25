package dev.turboism.bootstrap;

import dev.turboism.mapping.verification.VerifiedControlAppearanceResolverFactory;

/** Declarative contributor for the verified control-appearance hook. */
final class ControlAppearanceHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_CONTROL_APPEARANCE_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.fullRuntimeAdmission();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final var resolver = new VerifiedControlAppearanceResolverFactory().create(
            environment.verificationRecord(
                "cubism-" + environment.profile() + "-ui-control-appearance.json"
            ),
            host.artifact(),
            host.classLoader()
        );
        final long generation =
            runtime.hostAccess().paletteAppearanceCoordinator().hostGeneration();
        final VerifiedControlAppearanceHookInstaller installer =
            VerifiedControlAppearanceHookInstaller.fromVerifiedResolver(
                environment.instrumentation(),
                resolver,
                generation,
                runtime.hostAccess().paletteAppearanceCoordinator()
            );
        installer.install();
        return installer;
    }
}
