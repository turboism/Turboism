package dev.turboism.bootstrap;

/** Declarative contributor for the validation performance probe. */
final class PerformanceProbeHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_PERFORMANCE_PROBE";
    }

    @Override public Phase phase() {
        return Phase.RUNTIME_STARTED;
    }

    @Override public boolean admitted(final HookEnvironment environment) {
        return environment.fullRuntimeAdmission()
            && environment.options().performanceProbeInstall();
    }

    @Override public AutoCloseable install(final HookEnvironment environment) throws Exception {
        final var host = environment.host().orElseThrow();
        final var options = environment.options();
        final VerifiedPerformanceProbeInstaller installer =
            new VerifiedPerformanceProbeInstaller(
                environment.instrumentation(),
                host.artifact(),
                host.classLoader(),
                options.home().resolve("lib/performance-probe-carrier.jar")
            );
        installer.install(
            options.performanceProbeCapture(),
            options.performanceProbeScenario(),
            options.performanceProbeAgentSha256(),
            options.performanceProbeFixtureSha256(),
            java.time.Duration.ofSeconds(options.performanceProbeDelaySeconds()),
            java.time.Duration.ofSeconds(options.performanceProbeDurationSeconds()),
            options.performanceProbeOutput(),
            options.performanceProbeRunId(),
            options.performanceProbeRollbackOutput()
        );
        environment.runtime().ifPresent(runtime -> runtime.info(
            "bootstrap",
            "Turboism validation performance probe installed; capture="
                + options.performanceProbeCapture()
        ));
        return installer;
    }
}
