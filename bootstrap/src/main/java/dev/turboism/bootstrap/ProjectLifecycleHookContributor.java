package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.lifecycle.ProjectLifecycleHostProfile;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Declarative contributor for the verified project lifecycle hook. */
final class ProjectLifecycleHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_PROJECT_LIFECYCLE_HOOK";
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
        final var profile = ProjectLifecycleHostProfile.forArtifact(
            HostArtifactDigest.from(host.artifact())
        ).orElseThrow(() -> new IllegalStateException(
            "Unsupported project lifecycle host artifact"
        ));
        final VerifiedProjectLifecycleHookInstaller installer =
            new VerifiedProjectLifecycleHookInstaller(
                environment.instrumentation(),
                host.classLoader(),
                profile,
                runtime.hostAccess().projectFileLifecycle(),
                runtime.hostAccess().editorLifecycleEvents()
            );
        installer.install();
        runtime.info("bootstrap", id() + " installation=COMPLETE");
        return installer;
    }
}
