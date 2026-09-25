package dev.turboism.bootstrap;

/** Declarative contributor for the verified parameter lifecycle hook. */
final class ParameterHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_PARAMETER_HOOK";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean closesOnProcessExit() {
        return true;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.ordinaryReviewedRuntimeAdmitted();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var runtime = environment.runtime().orElseThrow();
        final var host = environment.host().orElseThrow();
        final VerifiedParameterHookInstaller installer =
            VerifiedParameterHookInstaller.fromVerifiedResolver(
                environment.instrumentation(),
                runtime.editorModelResolver(),
                host.classLoader(),
                runtime.hostAccess().parameterLifecycle(),
                runtime.hostAccess().modelAccess()
            );
        installer.install();
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return installer;
    }
}
