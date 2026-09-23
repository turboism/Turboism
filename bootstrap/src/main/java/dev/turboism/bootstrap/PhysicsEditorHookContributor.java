package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.physics.PhysicsEditorHostProfile;
import dev.turboism.mapping.verification.HostArtifactDigest;

/** Declarative contributor for the verified Physics Settings editor hook. */
final class PhysicsEditorHookContributor implements HookContributor {

    @Override public String id() {
        return "TURBOISM_PHYSICS_EDITOR_HOOK";
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
        final PhysicsEditorHostProfile profile = PhysicsEditorHostProfile.forArtifact(
            HostArtifactDigest.from(host.artifact())
        ).orElseThrow(() -> new IllegalStateException(
            "Unsupported Physics Settings host artifact"
        ));
        final VerifiedPhysicsEditorHookInstaller installer =
            new VerifiedPhysicsEditorHookInstaller(
                environment.instrumentation(),
                host.classLoader(),
                runtime.hostAccess().physicsEditorCoordinator(),
                profile
            );
        installer.install();
        return installer;
    }
}
